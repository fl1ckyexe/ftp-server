package org.example.ftp.server.command.handler;

import org.example.ftp.common.protocol.FtpResponse;
import org.example.ftp.server.auth.Permission;
import org.example.ftp.server.fs.PathResolver;
import org.example.ftp.server.session.FtpSession;

import java.nio.file.Files;
import java.nio.file.Path;

public class CwdCommandHandler extends AbstractCommandHandler {

    @Override
    public String getCommandName() {
        return "CWD";
    }

    @Override
    protected boolean checkState(FtpSession session) {
        return super.checkState(session);
    }

    @Override
    protected FtpResponse execute(FtpSession session, String argument) {

        try {
            if (argument == null || argument.isBlank()
                    || argument.equals("/") || argument.equals("\\")) {

                session.setCurrentDirectory(session.getHomeDirectory());
                // Сбрасываем флаг, так как мы вернулись в корень
                session.resetDirectoryChangeFlag();
                return FtpResponse.ok(250, "Directory successfully changed.");
            }

            Path target;
            try {
                System.out.println("CwdCommandHandler - Resolving path: " + argument);
                target = PathResolver.resolve(session, argument);
                System.out.println("CwdCommandHandler - Resolved to: " + target);
            } catch (SecurityException e) {
                System.err.println("CwdCommandHandler - SecurityException: " + e.getMessage());
                return FtpResponse.error(550, "Access denied.");
            }

            // Проверяем права доступа в зависимости от целевой директории
            Path home = session.getHomeDirectory().normalize().toAbsolutePath();
            Path shared = session.getSharedDirectory().normalize().toAbsolutePath();
            Path resolved = target.normalize().toAbsolutePath();
            boolean isInHomeDirectory = resolved.startsWith(home);
            boolean isInSharedDirectory = resolved.startsWith(shared);

            // Если переход в home directory, всегда разрешаем (не проверяем глобальные права)
            // Если переход в /shared, проверяем глобальное право READ (для просмотра)
            if (!isInHomeDirectory && isInSharedDirectory && !session.getPermissionService().has(session.getUsername(), Permission.READ)) {
                System.out.println("CwdCommandHandler - Permission denied: no READ permission for shared directory");
                return FtpResponse.error(550, "Permission denied.");
            }

            System.out.println("CwdCommandHandler - Checking if directory exists: " + target);
            if (!Files.isDirectory(target)) {
                System.err.println("CwdCommandHandler - Not a directory: " + target);
                return FtpResponse.error(550, "Not a directory.");
            }
            System.out.println("CwdCommandHandler - Directory exists, changing to: " + target);

            session.setCurrentDirectory(target);
            // Отмечаем, что директория была явно изменена
            session.markDirectoryChanged();
            return FtpResponse.ok(250, "Directory successfully changed.");

        } catch (Exception e) {
            System.err.println("CwdCommandHandler - Exception: " + e.getMessage());
            e.printStackTrace();
            return FtpResponse.error(550, "Failed to change directory.");
        }
    }

    @Override
    protected FtpResponse notAllowed() {
        return FtpResponse.needLogin();
    }
}