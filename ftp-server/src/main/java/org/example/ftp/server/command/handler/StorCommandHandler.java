package org.example.ftp.server.command.handler;

import org.example.ftp.common.protocol.FtpResponse;
import org.example.ftp.common.protocol.Responses;
import org.example.ftp.server.auth.Permission;
import org.example.ftp.server.fs.AccessControl;
import org.example.ftp.server.fs.PathResolver;
import org.example.ftp.server.session.FtpSession;
import org.example.ftp.server.transfer.RateLimiter;
import org.example.ftp.server.transfer.ThrottledInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class StorCommandHandler extends AbstractCommandHandler {

    @Override
    public String getCommandName() {
        return "STOR";
    }

    @Override
    protected boolean checkState(FtpSession session) {
        return super.checkState(session);
    }

    @Override
    protected FtpResponse execute(FtpSession session, String argument) {
        if (session.getPassiveDataSocket() == null) {
            return Responses.usePasvFirst();
        }

        if (argument == null || argument.isBlank()) {
            return Responses.missingFileName();
        }

        Path target;
        try {
            target = PathResolver.resolve(session, argument);
        } catch (SecurityException e) {
            return Responses.accessDenied();
        }

        if (!AccessControl.can(session, target, Permission.WRITE)) {
            return Responses.permissionDenied();
        }

        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }

            // Отправляем ответ 150 ДО вызова accept(), чтобы клиент знал, что нужно подключиться
            session.sendResponse(Responses.ok(150, "Opening data connection."));
        } catch (IOException e) {
            return Responses.connectionClosedTransferAborted();
        }
        
        // new transfer begins: clear any previous abort request
        session.clearTransferAbort();
        session.setActiveTransferThread(Thread.currentThread());

        boolean transferCompleted = false;
        boolean wasAborted = false;
        boolean eofReceived = false; // Отслеживаем, был ли получен EOF (успешное завершение передачи)
        OutputStream fileOutputStream = null;
        Socket activeDataConn = null;
        long bytes = 0;
        
        try (
                Socket dataConnection = session.getPassiveDataSocket().accept();
                InputStream rawIn = dataConnection.getInputStream();
                InputStream in = wrapInputWithLimiter(session, rawIn)
        ) {
            activeDataConn = dataConnection;
            session.setActiveDataConnection(dataConnection);

            // Создаем поток файла отдельно, чтобы иметь контроль над его закрытием
            fileOutputStream = Files.newOutputStream(
                    target,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
            // Устанавливаем короткий таймаут на сокет для частой проверки состояния
            dataConnection.setSoTimeout(50); // 50мс таймаут для частой проверки состояния
            // Используем буферизованное чтение/запись вместо transferTo для правильной работы rate limiting
            // Используем очень маленький буфер (512 байт) для минимальной блокировки и максимальной отзывчивости UI
            byte[] buffer = new byte[512];
            int bytesRead;
            try {
                while (true) {
                    // ABOR from control connection
                    if (session.isTransferAbortRequested()) {
                        wasAborted = true;
                        break;
                    }
                    // Проверяем состояние соединения ПЕРЕД каждым чтением
                    if (dataConnection.isClosed() || !dataConnection.isConnected()) {
                        wasAborted = true;
                        break;
                    }
                    
                    try {
                        bytesRead = in.read(buffer);
                        if (bytesRead == -1) {
                            // EOF means client finished sending. If it was a cancel/abort, we'll also see closed/reset or abort flag.
                            eofReceived = true; // Отметим, что EOF был получен
                            if (session.isTransferAbortRequested()) {
                                wasAborted = true;
                            }
                            break;
                        }
                    } catch (java.net.SocketTimeoutException e) {
                        // Таймаут - проверяем состояние соединения
                        if (session.isTransferAbortRequested()) {
                            wasAborted = true;
                            break;
                        }
                        if (dataConnection.isClosed() || !dataConnection.isConnected()) {
                            wasAborted = true;
                            break;
                        }
                        // Если соединение еще открыто, продолжаем чтение
                        continue;
                    }
                    
                    fileOutputStream.write(buffer, 0, bytesRead);
                    bytes += bytesRead;
                }
            } catch (IOException e) {
                // Если соединение закрыто клиентом (отмена), это нормально
                String msg = e.getMessage();
                if (session.isTransferAbortRequested()) {
                    wasAborted = true;
                } else if (dataConnection.isClosed() || !dataConnection.isConnected() || 
                    (msg != null && (msg.contains("closed") || msg.contains("reset") || msg.contains("Connection reset")))) {
                    wasAborted = true;
                } else {
                    throw e; // Другая ошибка - пробрасываем дальше
                }
            }
            
            if (wasAborted) {
                // Помечаем, что передача не завершена
                transferCompleted = false;
            } else {
                if (fileOutputStream != null) {
                    fileOutputStream.flush();
                }
                transferCompleted = true; // Передача успешно завершена
                session.getStatsService().onUpload(session.getUsername(), bytes);
            }
        } catch (SocketTimeoutException e) {
            // Client didn't open data connection in time
            wasAborted = true;
            transferCompleted = false;
        } catch (IOException e) {
            // Если EOF был получен (передача завершена успешно), игнорируем ошибки закрытия соединения
            if (eofReceived && transferCompleted) {
                // Передача уже завершена успешно, ошибка при закрытии соединения не важна
                // Просто игнорируем ошибку
            } else {
                // При ошибке до завершения передачи помечаем как прерванную
                String msg = e.getMessage();
                // Игнорируем ошибки закрытия соединения, если EOF был получен
                if (msg != null && (msg.contains("closed") || msg.contains("reset") || msg.contains("Connection reset") || msg.contains("Broken pipe"))) {
                    if (eofReceived) {
                        // EOF был получен, это нормальное закрытие соединения после завершения передачи
                        // Не помечаем как ошибку
                    } else {
                        // EOF не был получен, это реальная ошибка
                        wasAborted = true;
                        transferCompleted = false;
                    }
                } else {
                    wasAborted = true;
                    transferCompleted = false;
                }
            }
        } finally {
            // Clear active data connection reference
            if (activeDataConn != null) {
                session.clearActiveDataConnection(activeDataConn);
            }
            session.clearActiveTransferThread(Thread.currentThread());

            // Закрываем поток файла ПЕРЕД удалением
            if (fileOutputStream != null) {
                try {
                    fileOutputStream.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
            
            // Закрываем пассивный сокет
            try {
                session.closePassiveDataSocket();
            } catch (IOException ignored) {}
            
            // Удаляем файл если передача была прервана (не блокируем поток на секунды)
            if (!transferCompleted || wasAborted) {
                deletePartialFileAsync(target);
            }
        }

        if (wasAborted || !transferCompleted) {
            return Responses.connectionClosedTransferAborted();
        }

        return Responses.transferComplete();
    }

    private InputStream wrapInputWithLimiter(FtpSession session, InputStream in) {
        // Upload limiter
        RateLimiter limiter = session.getUploadRateLimiter();
        if (limiter == null) {
            return in;
        }
        return new ThrottledInputStream(in, limiter);
    }

    private void deletePartialFileAsync(Path target) {
        // Best-effort deletion; on Windows the file may still be locked briefly.
        Thread t = new Thread(() -> {
            for (int attempt = 1; attempt <= 10; attempt++) {
                try {
                    Files.deleteIfExists(target);
                    return;
                } catch (IOException e) {
                    if (attempt == 10) {
                        return;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "stor-delete-partial");
        t.setDaemon(true);
        t.start();
    }

    @Override
    protected FtpResponse notAllowed() {
        return Responses.needLogin();
    }
}