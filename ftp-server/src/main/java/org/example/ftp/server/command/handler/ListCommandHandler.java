package org.example.ftp.server.command.handler;

import org.example.ftp.common.protocol.FtpResponse;
import org.example.ftp.common.protocol.Responses;
import org.example.ftp.server.auth.Permission;
import org.example.ftp.server.fs.ListFormatter;
import org.example.ftp.server.fs.AccessControl;
import org.example.ftp.server.fs.PathResolver;
import org.example.ftp.server.session.FtpSession;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public class ListCommandHandler extends AbstractCommandHandler {

    @Override
    public String getCommandName() {
        return "LIST";
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

        Path dir;
        try {
            dir = PathResolver.resolve(session, argument);
        } catch (SecurityException e) {
            return Responses.accessDenied();
        }

        if (!AccessControl.can(session, dir, Permission.READ)) {
            return Responses.permissionDenied();
        }

        try (
                Socket dataConnection = session.getPassiveDataSocket().accept();
                PrintWriter out = new PrintWriter(
                        new OutputStreamWriter(dataConnection.getOutputStream(), StandardCharsets.UTF_8),
                        true
                )
        ) {
            Path home = session.getHomeDirectory().normalize().toAbsolutePath();
            Path shared = session.getSharedDirectory().normalize().toAbsolutePath();
            Path requested = dir.normalize().toAbsolutePath();

            // Если запрашивается home directory, показываем только реальное содержимое
            // НЕ показываем виртуальные папки владельцев - они должны быть только в разделе user-to-user в UI
            if (requested.equals(home)) {
                // Показываем только реальные файлы и папки из home directory пользователя
                // Фильтруем, чтобы не показывать папки других пользователей
                Path ftpRoot = session.getFtpRoot().normalize().toAbsolutePath();
                Path usersDir = ftpRoot.resolve("users").normalize().toAbsolutePath();
                String currentUsername = session.getUsername();
                
                try (Stream<Path> stream = Files.list(dir)) {
                    stream
                        .filter(p -> {
                            Path normalized = p.normalize().toAbsolutePath();
                            
                            // Показываем только файлы/папки, которые находятся строго в home directory пользователя
                            // Исключаем папки других пользователей
                            if (normalized.startsWith(usersDir)) {
                                Path relative = usersDir.relativize(normalized);
                                if (relative.getNameCount() > 0) {
                                    String firstComponent = relative.getName(0).toString();
                                    // Показываем только если это папка текущего пользователя или его подпапка
                                    if (!firstComponent.equals(currentUsername)) {
                                        // Это папка другого пользователя - не показываем
                                        return false;
                                    }
                                }
                            }
                            
                            // Показываем только если путь находится в home directory пользователя
                            return normalized.startsWith(home);
                        })
                        .map(ListFormatter::format)
                        .forEach(out::println);
                }
                out.flush();
            }
            // Если запрашивается /shared, показываем физические файлы и папки
            // (глобальное право READ уже проверено выше, поэтому здесь мы можем показывать всё)
            else if (requested.equals(shared)) {
                try (Stream<Path> stream = Files.list(dir)) {
                    stream.map(ListFormatter::format).forEach(out::println);
                }
                out.flush();
            }
            // Для всех остальных директорий показываем обычный список
            // Если это папка другого пользователя, AccessControl уже проверил доступ через shared_folders
            // Поэтому показываем все содержимое (доступ уже разрешен)
            else {
                try (Stream<Path> stream = Files.list(dir)) {
                    stream.map(ListFormatter::format).forEach(out::println);
                }
                out.flush();
            }

        } catch (SocketTimeoutException e) {
            return Responses.connectionClosedTransferAborted();
        } catch (Exception e) {
            return Responses.connectionClosedTransferAborted();
        } finally {
            try {
                session.closePassiveDataSocket();
            } catch (Exception ignored) {}
        }

        return Responses.directorySendOk();
    }

    @Override
    protected FtpResponse notAllowed() {
        return Responses.needLogin();
    }
}