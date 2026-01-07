package org.example.ftp.server.command.handler;

import org.example.ftp.common.protocol.FtpResponse;
import org.example.ftp.server.auth.Permission;
import org.example.ftp.server.fs.PathResolver;
import org.example.ftp.server.session.FtpSession;

import java.nio.file.Files;
import java.nio.file.Path;

public class DeleCommandHandler extends AbstractCommandHandler {

    @Override
    public String getCommandName() {
        return "DELE";
    }

    @Override
    protected boolean checkState(FtpSession session) {
        return super.checkState(session);
    }

    @Override
    protected FtpResponse execute(FtpSession session, String argument) {

        if (argument == null || argument.isBlank()) {
            return FtpResponse.error(501, "File name required.");
        }

        Path target;
        try {
            target = PathResolver.resolve(session, argument);
        } catch (SecurityException e) {
            return FtpResponse.error(550, "Access denied.");
        }

        // Проверяем, находится ли путь в home directory пользователя
        Path home = session.getHomeDirectory().normalize().toAbsolutePath();
        Path resolved = target.normalize().toAbsolutePath();
        boolean isInHomeDirectory = resolved.startsWith(home);

        // Если путь находится в home directory, всегда разрешаем (не проверяем глобальные права)
        // Если путь находится вне home directory (например, /shared), проверяем глобальное право EXECUTE (для удаления)
        if (!isInHomeDirectory && !session.getPermissionService().has(session.getUsername(), Permission.EXECUTE)) {
            return FtpResponse.error(550, "Permission denied.");
        }

        if (!Files.exists(target) || !Files.isRegularFile(target)) {
            return FtpResponse.error(550, "File not found.");
        }

        try {
            Files.delete(target);
            return FtpResponse.ok(250, "File deleted.");
        } catch (Exception e) {
            return FtpResponse.error(550, "Delete failed.");
        }
    }

    @Override
    protected FtpResponse notAllowed() {
        return FtpResponse.needLogin();
    }
}