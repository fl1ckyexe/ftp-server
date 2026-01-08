package org.example.ftp.server.fs;

import org.example.ftp.server.auth.Permission;
import org.example.ftp.server.session.FtpSession;
import org.example.ftp.server.util.DebugLog;

import java.nio.file.Path;

 
public final class AccessControl {

    private AccessControl() {}

    public static boolean can(FtpSession session, Path resolvedPath, Permission required) {
        String username = session.getUsername();
        Path resolved = resolvedPath.normalize().toAbsolutePath();
        
        DebugLog.d("AccessControl.can() - username=" + username + " path=" + resolved + " permission=" + required);
        
        Path ftpRoot = session.getFtpRoot().normalize().toAbsolutePath();
        Path sharedDir = session.getSharedDirectory().normalize().toAbsolutePath();
        Path usersDir = ftpRoot.resolve("users").normalize().toAbsolutePath();
        Path home = session.getHomeDirectory().normalize().toAbsolutePath();
        
        // 1) Home directory: all permissions always allowed (no checks)
        if (resolved.startsWith(home)) {
            DebugLog.d("AccessControl.can() - ALLOWED: path is in own home directory, all permissions allowed");
            return true;
        }
        
        // 2) /shared directory: check global permissions from DB
        if (resolved.startsWith(sharedDir)) {
            boolean hasGlobalPermission = session.getPermissionService().has(username, required);
            DebugLog.d("AccessControl.can() - path is in /shared, hasGlobalPermission=" + hasGlobalPermission);
            return hasGlobalPermission;
        }
        
        // 3) If not in usersDir at all (e.g., root level), check global permissions
        if (!resolved.startsWith(usersDir)) {
            boolean hasGlobalPermission = session.getPermissionService().has(username, required);
            DebugLog.d("AccessControl.can() - path not in usersDir, hasGlobalPermission=" + hasGlobalPermission);
            return hasGlobalPermission;
        }

        // 4) Other user's directory (user-to-user): check ONLY permissions from shared_folders (ignore global)
        String rel = usersDir.relativize(resolved).toString().replace('\\', '/');
        String ftpStyle = "/" + rel;

        var currentUserOpt = session.getUserRepository().findByUsername(username);
        if (currentUserOpt.isEmpty()) {
            DebugLog.d("AccessControl.can() - DENIED: current user not found in repository");
            return false;
        }
        long currentUserId = currentUserOpt.get().id();

        // Проверяем доступ через shared_folders
        boolean hasSharePermission = session.getSharedFolderRepository().hasPermission(currentUserId, ftpStyle, required);
        
        // Если нет прямого доступа, проверяем, не является ли это home directory другого пользователя
        // и есть ли у текущего пользователя хотя бы одна поделенная папка от этого владельца
        if (!hasSharePermission && required == Permission.READ) {
            // Извлекаем имя владельца из пути (первый компонент)
            String[] parts = rel.split("/");
            if (parts.length > 0 && !parts[0].equals(username)) {
                String ownerUsername = parts[0];
                // Проверяем, есть ли у текущего пользователя хотя бы одна поделенная папка от этого владельца
                var sharedFolders = session.getSharedFolderRepository().findByUserToShare(currentUserId);
                for (var sf : sharedFolders) {
                    var ownerOpt = session.getUserRepository().findById(sf.ownerUserId());
                    if (ownerOpt.isPresent() && ownerOpt.get().username().equals(ownerUsername)) {
                        // Нашли поделенную папку от этого владельца - разрешаем доступ к его home directory
                        DebugLog.d("AccessControl.can() - ALLOWED: access to owner's home directory, has shared folders from " + ownerUsername);
                        return true;
                    }
                }
            }
        }
        
        DebugLog.d("AccessControl.can() - user-to-user folder, hasSharePermission=" + hasSharePermission + " for path=" + ftpStyle);
        
        return hasSharePermission;
    }
}


