package org.example.ftp.server.command.handler;

import org.example.ftp.common.protocol.FtpResponse;
import org.example.ftp.common.protocol.Responses;
import org.example.ftp.server.auth.Permission;
import org.example.ftp.server.fs.PathResolver;
import org.example.ftp.server.fs.log.ServerLogService;
import org.example.ftp.server.session.FtpSession;
import org.example.ftp.server.transfer.RateLimiter;
import org.example.ftp.server.transfer.ThrottledInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
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

        // Проверяем, находится ли путь в home directory пользователя
        Path home = session.getHomeDirectory().normalize().toAbsolutePath();
        Path shared = session.getSharedDirectory().normalize().toAbsolutePath();
        Path resolved = target.normalize().toAbsolutePath();
        boolean isInHomeDirectory = resolved.startsWith(home);
        boolean isInSharedDirectory = resolved.startsWith(shared);

        // Если путь находится в home directory, всегда разрешаем (не проверяем глобальные права)
        // Если путь находится в /shared, проверяем глобальное право WRITE
        if (!isInHomeDirectory && isInSharedDirectory && !session.getPermissionService().has(session.getUsername(), Permission.WRITE)) {
            return Responses.permissionDenied();
        }

        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }

            // Отправляем ответ 150 ДО вызова accept(), чтобы клиент знал, что нужно подключиться
            session.sendResponse(Responses.ok(150, "Opening data connection."));
        } catch (IOException e) {
            ServerLogService.log("[STOR] ERROR before transfer: " + e.getMessage());
            return Responses.connectionClosedTransferAborted();
        }
        
        // new transfer begins: clear any previous abort request
        session.clearTransferAbort();
        session.setActiveTransferThread(Thread.currentThread());

        boolean transferCompleted = false;
        boolean wasAborted = false;
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

            ServerLogService.log("[STOR] Start upload user=" + session.getUsername() + " file=" + argument);
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
                            if (session.isTransferAbortRequested()) {
                                System.out.println("[STOR] EOF received but ABOR was requested, aborting transfer");
                                wasAborted = true;
                            }
                            break;
                        }
                    } catch (java.net.SocketTimeoutException e) {
                        // Таймаут - проверяем состояние соединения
                        if (session.isTransferAbortRequested()) {
                            System.out.println("[STOR] Transfer abort requested (ABOR) during timeout, aborting transfer");
                            wasAborted = true;
                            break;
                        }
                        if (dataConnection.isClosed() || !dataConnection.isConnected()) {
                            System.out.println("[STOR] Data connection closed by client (timeout), aborting transfer");
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
                    ServerLogService.log("[STOR] ERROR during transfer: " + msg);
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
        } catch (IOException e) {
            // При любой ошибке помечаем как прерванную
            ServerLogService.log("[STOR] ERROR during transfer: " + e.getMessage());
            wasAborted = true;
            transferCompleted = false;
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
                    ServerLogService.log("[STOR] ERROR closing file: " + e.getMessage());
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
            ServerLogService.log("[STOR] Aborted user=" + session.getUsername() + " file=" + argument + " bytes=" + bytes);
            return Responses.connectionClosedTransferAborted();
        }

        ServerLogService.log("[STOR] Complete user=" + session.getUsername() + " file=" + argument + " bytes=" + bytes);
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
                        ServerLogService.log("[STOR] ERROR: Could not delete partial file: " + target + " (" + e.getMessage() + ")");
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