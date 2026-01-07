package org.example.ftp.server.command.handler;

import org.example.ftp.common.protocol.FtpResponse;
import org.example.ftp.common.protocol.Responses;
import org.example.ftp.server.auth.Permission;
import org.example.ftp.server.fs.ListFormatter;
import org.example.ftp.server.fs.PathResolver;
import org.example.ftp.server.session.FtpSession;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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

        Path home = session.getHomeDirectory().normalize().toAbsolutePath();
        Path shared = session.getSharedDirectory().normalize().toAbsolutePath();
        Path resolved = dir.normalize().toAbsolutePath();
        boolean isInHomeDirectory = resolved.startsWith(home);
        boolean isInSharedDirectory = resolved.startsWith(shared);

        // Если путь находится в home directory, всегда разрешаем (не проверяем глобальные права)
        // Если путь находится в /shared, проверяем глобальное право READ
        if (!isInHomeDirectory && isInSharedDirectory && !session.getPermissionService().has(session.getUsername(), Permission.READ)) {
            return Responses.permissionDenied();
        }

        try (
                Socket dataConnection = session.getPassiveDataSocket().accept();
                PrintWriter out = new PrintWriter(
                        new OutputStreamWriter(dataConnection.getOutputStream(), StandardCharsets.UTF_8),
                        true
                )
        ) {
            Path requested = resolved;
            Path currentDir = session.getCurrentDirectory().normalize().toAbsolutePath();
            
            System.out.println("LIST command - requested path: " + requested);
            System.out.println("LIST command - current directory: " + currentDir);
            System.out.println("LIST command - home path: " + home);
            System.out.println("LIST command - shared path: " + shared);
            System.out.println("LIST command - argument was: " + argument);

            // Если запрашивается home directory, показываем реальное содержимое home directory
            if (requested.equals(home)) {
                System.out.println("LIST: Showing home directory contents");
                // Показываем только реальные файлы и папки из home directory
                Files.list(dir)
                        .peek(path -> System.out.println("LIST: Adding file: " + path.getFileName()))
                        .map(ListFormatter::format)
                        .peek(formatted -> System.out.println("LIST: Formatted: " + formatted))
                        .forEach(out::println);
                out.flush();
                System.out.println("LIST: Home directory listing sent");
            }
            // Если запрашивается /shared, показываем физические файлы и папки
            // (глобальное право READ уже проверено выше, поэтому здесь мы можем показывать всё)
            else if (requested.equals(shared)) {
                System.out.println("LIST: Showing shared directory contents");
                
                // Показываем все физические файлы и папки из /shared
                // (пользователь уже имеет глобальное право READ, так как мы прошли проверку выше)
                Files.list(dir)
                        .peek(path -> System.out.println("LIST: Adding file: " + path.getFileName()))
                        .map(ListFormatter::format)
                        .forEach(out::println);
                
                out.flush();
                System.out.println("LIST: Shared directory listing sent");
            }
            // Для всех остальных директорий показываем обычный список
            else {
                System.out.println("LIST: Showing regular directory listing for: " + requested);
                long count = Files.list(dir)
                        .peek(path -> System.out.println("LIST: Adding file: " + path.getFileName()))
                        .map(ListFormatter::format)
                        .peek(formatted -> System.out.println("LIST: Formatted: " + formatted))
                        .peek(out::println)
                        .count();
                System.out.println("LIST: Sent " + count + " files");
                out.flush();
            }
            
            System.out.println("LIST: Listing complete, closing data connection");

        } catch (Exception e) {
            System.err.println("LIST: Exception occurred: " + e.getMessage());
            e.printStackTrace();
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