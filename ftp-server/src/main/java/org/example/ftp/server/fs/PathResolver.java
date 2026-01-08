package org.example.ftp.server.fs;

import org.example.ftp.server.session.FtpSession;

import java.nio.file.Path;

public final class PathResolver {

    private PathResolver() {}

    public static Path resolve(FtpSession session, String ftpPath) {
        Path base;

        if (ftpPath == null || ftpPath.isBlank()) {
            base = session.getCurrentDirectory();
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
                        // Получаем ID текущего пользователя
                        var currentUserOpt = session.getUserRepository().findByUsername(session.getUsername());
                        if (currentUserOpt.isEmpty()) {
                            throw new SecurityException("User not found");
                        }
                        
                        long currentUserId = currentUserOpt.get().id();
                        
                        // Проверяем доступ через shared folders
                        if (session.getSharedFolderRepository().hasAccess(currentUserId, normalized)) {
                            // Разрешаем путь: преобразуем /admin/lasttest в путь на диске
                            // Формат: /owner/path -> ftpRoot/users/owner/path
                            Path ownerHome = session.getFtpRoot().resolve("users").resolve(ownerUsername);
                            String subPath = firstSlash > 0 ? relative.substring(firstSlash + 1) : "";
                            if (!subPath.isEmpty()) {
                                base = ownerHome.resolve(subPath);
                            } else {
                                base = ownerHome;
                            }
                        } else {
                            throw new SecurityException("Access denied: " + normalized);
                        }
                    } else {
                        // Путь начинается с имени текущего пользователя, но мы уже обработали это выше
                        // Это не должно случиться, но на всякий случай
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
                base = session.getHomeDirectory();
            } else {
                // Разрешаем как обычный относительный путь
                base = session.getCurrentDirectory().resolve(ftpPath);
            }
        }

        Path normalized = base.normalize().toAbsolutePath();

        Path home = session.getHomeDirectory().normalize().toAbsolutePath();
        Path shared = session.getSharedDirectory().normalize().toAbsolutePath();
        Path ftpRoot = session.getFtpRoot().normalize().toAbsolutePath();
        Path usersDir = ftpRoot.resolve("users").normalize().toAbsolutePath();

        // Разрешаем доступ к home directory, shared directory, и папкам других пользователей (shared folders)
        if (!normalized.startsWith(home) && !normalized.startsWith(shared) && !normalized.startsWith(usersDir)) {
            throw new SecurityException("Access outside allowed roots");
        }

        // Important: when user is already inside other user's directory, relative paths like ".."
        // must not allow escaping outside of granted shared folder roots.
        if (normalized.startsWith(usersDir) && !normalized.startsWith(home)) {
            String rel = usersDir.relativize(normalized).toString().replace('\\', '/');
            String ftpStyle = "/" + rel;

            var currentUserOpt = session.getUserRepository().findByUsername(session.getUsername());
            if (currentUserOpt.isEmpty()) {
                throw new SecurityException("User not found");
            }
            long currentUserId = currentUserOpt.get().id();

            // Проверяем доступ через shared_folders
            boolean hasAccess = session.getSharedFolderRepository().hasAccess(currentUserId, ftpStyle);
            
            // Если нет прямого доступа, проверяем, не является ли это home directory другого пользователя
            // и есть ли у текущего пользователя хотя бы одна поделенная папка от этого владельца
            if (!hasAccess) {
                String[] parts = rel.split("/");
                if (parts.length > 0 && !parts[0].equals(session.getUsername())) {
                    String ownerUsername = parts[0];
                    // Проверяем, есть ли у текущего пользователя хотя бы одна поделенная папка от этого владельца
                    var sharedFolders = session.getSharedFolderRepository().findByUserToShare(currentUserId);
                    for (var sf : sharedFolders) {
                        var ownerOpt = session.getUserRepository().findById(sf.ownerUserId());
                        if (ownerOpt.isPresent() && ownerOpt.get().username().equals(ownerUsername)) {
                            // Нашли поделенную папку от этого владельца - разрешаем доступ к его home directory
                            hasAccess = true;
                            break;
                        }
                    }
                }
            }
            
            if (!hasAccess) {
                throw new SecurityException("Access denied: " + ftpStyle);
            }
        }

        return normalized;
    }
}