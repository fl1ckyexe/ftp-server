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

public class RetrCommandHandler extends AbstractCommandHandler {

    @Override
    public String getCommandName() {
        return "RETR";
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

        Path file;
        try {
            file = PathResolver.resolve(session, argument);
        } catch (SecurityException e) {
            return Responses.accessDenied();
        }

        // Проверяем, находится ли путь в home directory пользователя
        Path home = session.getHomeDirectory().normalize().toAbsolutePath();
        Path shared = session.getSharedDirectory().normalize().toAbsolutePath();
        Path resolved = file.normalize().toAbsolutePath();
        boolean isInHomeDirectory = resolved.startsWith(home);
        boolean isInSharedDirectory = resolved.startsWith(shared);

        // Если путь находится в home directory, всегда разрешаем (не проверяем глобальные права)
        // Если путь находится в /shared, проверяем глобальное право READ
        if (!isInHomeDirectory && isInSharedDirectory && !session.getPermissionService().has(session.getUsername(), Permission.READ)) {
            return Responses.permissionDenied();
        }

        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            return Responses.fileNotFound();
        }

        // new transfer begins: clear any previous abort request
        session.clearTransferAbort();
        session.setActiveTransferThread(Thread.currentThread());
        boolean transferCompleted = false;
        
        try {
            // Отправляем ответ 150 ДО вызова accept(), чтобы клиент знал, что нужно подключиться
            session.sendResponse(Responses.ok(150, "Opening data connection."));
            
            try (
                    Socket dataConnection = session.getPassiveDataSocket().accept();
                    InputStream rawIn = Files.newInputStream(file);
                    InputStream in = wrapInputWithLimiter(session, rawIn);
                    OutputStream out = dataConnection.getOutputStream()
            ) {
                session.setActiveDataConnection(dataConnection);
                ServerLogService.log("[RETR] Start download user=" + session.getUsername() + " file=" + argument);
                // Используем буферизованное чтение/запись вместо transferTo для правильной работы rate limiting
                // Используем меньший буфер для более точного контроля скорости
                byte[] buffer = new byte[4096];
                long bytes = 0;
                int bytesRead;
                
                try {
                    while ((bytesRead = in.read(buffer)) != -1) {
                        if (session.isTransferAbortRequested()) {
                            transferCompleted = false;
                            break;
                        }
                        // Проверяем состояние соединения перед записью
                        if (dataConnection.isClosed() || !dataConnection.isConnected()) {
                            break;
                        }
                        out.write(buffer, 0, bytesRead);
                        bytes += bytesRead;
                    }
                    if (!session.isTransferAbortRequested()) {
                        out.flush();
                        transferCompleted = true;
                        session.getStatsService().onDownload(session.getUsername(), bytes);
                        ServerLogService.log("[RETR] Complete user=" + session.getUsername() + " file=" + argument + " bytes=" + bytes);
                    }
                } catch (IOException e) {
                    // Если соединение закрыто клиентом (отмена), это нормально
                    String msg = e.getMessage();
                    if (session.isTransferAbortRequested()) {
                        transferCompleted = false;
                    } else if (dataConnection.isClosed() || !dataConnection.isConnected() ||
                            (msg != null && (msg.contains("closed") || msg.contains("reset") || msg.contains("Connection reset")))) {
                        transferCompleted = false;
                    } else {
                        ServerLogService.log("[RETR] ERROR during transfer: " + msg);
                        throw e; // Другая ошибка - пробрасываем дальше
                    }
                }
            } finally {
                // best-effort: clear active data connection reference (it may already be closed)
                // we can't access the socket instance here directly, but ABOR will close it anyway
            }

        } catch (IOException e) {
            ServerLogService.log("[RETR] ERROR during transfer: " + e.getMessage());
            return Responses.connectionClosedTransferAborted();
        } finally {
            session.setActiveDataConnection(null);
            session.clearActiveTransferThread(Thread.currentThread());
            try {
                session.closePassiveDataSocket();
            } catch (IOException ignored) {}
        }

        if (transferCompleted) {
            return Responses.transferComplete();
        } else {
            ServerLogService.log("[RETR] Aborted user=" + session.getUsername() + " file=" + argument);
            return Responses.connectionClosedTransferAborted();
        }
    }

    private InputStream wrapInputWithLimiter(FtpSession session, InputStream in) {
        // Download limiter
        RateLimiter limiter = session.getDownloadRateLimiter();
        if (limiter == null) {
            return in;
        }
        return new ThrottledInputStream(in, limiter);
    }

    @Override
    protected FtpResponse notAllowed() {
        return Responses.needLogin();
    }
}