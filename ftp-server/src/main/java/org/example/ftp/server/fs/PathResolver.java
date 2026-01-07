package org.example.ftp.server.fs;

import org.example.ftp.server.session.FtpSession;
import org.example.ftp.server.util.DebugLog;

import java.nio.file.Path;

public final class PathResolver {

    private PathResolver() {}

    public static Path resolve(FtpSession session, String ftpPath) {
        DebugLog.d("PathResolver.resolve() - ftpPath: " + ftpPath);
        DebugLog.d("PathResolver.resolve() - currentDirectory: " + session.getCurrentDirectory());
        DebugLog.d("PathResolver.resolve() - homeDirectory: " + session.getHomeDirectory());
        DebugLog.d("PathResolver.resolve() - username: " + session.getUsername());

        Path base;

        if (ftpPath == null || ftpPath.isBlank()) {
            base = session.getCurrentDirectory();
            DebugLog.d("PathResolver.resolve() - Using current directory: " + base);
        }
        else if (ftpPath.startsWith("/")) {
            String normalized = ftpPath.replace('\\', '/');

            if (normalized.equals("/shared") || normalized.startsWith("/shared/")) {
                String relative = normalized.equals("/shared")
                        ? ""
                        : normalized.substring("/shared/".length());
                base = session.getSharedDirectory().resolve(relative);
            } else {
                String relative = normalized.substring(1); // drop leading '/'
                
                // Если путь равен имени текущего пользователя, это означает переход в home directory
                if (relative.equals(session.getUsername())) {
                    base = session.getHomeDirectory();
                } else if (relative.startsWith(session.getUsername() + "/")) {
                    // Путь вида /admin/... означает подпапку внутри home directory
                    String subPath = relative.substring(session.getUsername().length() + 1);
                    
                    // Проверяем, не пытается ли пользователь перейти в виртуальные папки
                    // Например, /admin/admin или /admin/shared - это недопустимые пути
                    if (subPath.equals("admin") || subPath.equals("shared") || 
                        subPath.startsWith("admin/") || subPath.startsWith("shared/")) {
                        DebugLog.d("PathResolver.resolve() - Attempting to access virtual folder: " + normalized + ", rejecting");
                        throw new SecurityException("Cannot access virtual folders: " + normalized);
                    }
                    
                    base = session.getHomeDirectory().resolve(subPath);
                } else {
                    // Если путь начинается не с имени текущего пользователя, проверяем shared folders
                    // Извлекаем имя владельца из пути (первый компонент после /)
                    int firstSlash = relative.indexOf('/');
                    String ownerUsername = firstSlash > 0 ? relative.substring(0, firstSlash) : (firstSlash == -1 ? relative : "");
                    
                    // Если это не имя текущего пользователя, проверяем доступ через shared folders
                    if (!ownerUsername.isEmpty() && !ownerUsername.equals(session.getUsername())) {
                        DebugLog.d("PathResolver.resolve() - Checking shared folder access for path: " + normalized);
                        
                        // Получаем ID текущего пользователя
                        var currentUserOpt = session.getUserRepository().findByUsername(session.getUsername());
                        if (currentUserOpt.isEmpty()) {
                            DebugLog.d("PathResolver.resolve() - Current user not found, rejecting");
                            throw new SecurityException("User not found");
                        }
                        
                        long currentUserId = currentUserOpt.get().id();
                        
                        // Проверяем доступ через shared folders
                        if (session.getSharedFolderRepository().hasAccess(currentUserId, normalized)) {
                            DebugLog.d("PathResolver.resolve() - Shared folder access granted for: " + normalized);
                            
                            // Разрешаем путь: преобразуем /admin/lasttest в путь на диске
                            // Формат: /owner/path -> ftpRoot/users/owner/path
                            Path ownerHome = session.getFtpRoot().resolve("users").resolve(ownerUsername);
                            String subPath = firstSlash > 0 ? relative.substring(firstSlash + 1) : "";
                            if (!subPath.isEmpty()) {
                                base = ownerHome.resolve(subPath);
                            } else {
                                base = ownerHome;
                            }
                            DebugLog.d("PathResolver.resolve() - Resolved shared folder path: " + base);
                        } else {
                            DebugLog.d("PathResolver.resolve() - No shared folder access for: " + normalized + ", rejecting");
                            throw new SecurityException("Access denied: " + normalized);
                        }
                    } else {
                        // Путь начинается с имени текущего пользователя, но мы уже обработали это выше
                        // Это не должно случиться, но на всякий случай
                        DebugLog.d("PathResolver.resolve() - Invalid path format: " + normalized + ", rejecting");
                        throw new SecurityException("Invalid path format: " + normalized);
                    }
                }
            }
        }
        else {
            String normalized = ftpPath.replace('\\', '/');

            // Проверяем, находимся ли мы в home directory
            Path currentDir = session.getCurrentDirectory().normalize().toAbsolutePath();
            Path homeDir = session.getHomeDirectory().normalize().toAbsolutePath();
            boolean isInHomeDirectory = currentDir.equals(homeDir);

            if ((normalized.equals("shared") || normalized.startsWith("shared/"))
                    && isInHomeDirectory) {

                String relative = normalized.equals("shared")
                        ? ""
                        : normalized.substring("shared/".length());
                base = session.getSharedDirectory().resolve(relative);
            } else if (normalized.equals(session.getUsername()) && isInHomeDirectory) {
                // Если пытаемся перейти в папку с именем пользователя, находясь уже в home directory,
                // это не имеет смысла - остаемся в home directory
                DebugLog.d("PathResolver.resolve() - Relative path equals username and in home, staying in home");
                base = session.getHomeDirectory();
            } else {
                DebugLog.d("PathResolver.resolve() - Resolving relative path: " + ftpPath + " from current: " + currentDir);
                base = session.getCurrentDirectory().resolve(ftpPath);
            }
        }

        Path normalized = base.normalize().toAbsolutePath();
        System.out.println("PathResolver.resolve() - Final resolved path: " + normalized);

        Path home = session.getHomeDirectory().normalize().toAbsolutePath();
        Path shared = session.getSharedDirectory().normalize().toAbsolutePath();
        Path ftpRoot = session.getFtpRoot().normalize().toAbsolutePath();
        Path usersDir = ftpRoot.resolve("users").normalize().toAbsolutePath();

        // Разрешаем доступ к home directory, shared directory, и папкам других пользователей (shared folders)
        if (!normalized.startsWith(home) && !normalized.startsWith(shared) && !normalized.startsWith(usersDir)) {
            throw new SecurityException("Access outside allowed roots");
        }

        return normalized;
    }
}