package org.example.ftp.server.http;

import com.sun.net.httpserver.HttpServer;
import org.example.ftp.server.auth.AuthService;
import org.example.ftp.server.auth.PermissionService;
import org.example.ftp.server.auth.db.SqliteFolderPermissionRepository;
import org.example.ftp.server.auth.db.SqliteFolderRepository;
import org.example.ftp.server.auth.db.SqliteSharedFolderRepository;
import org.example.ftp.server.auth.db.SqliteUserRepository;
import org.example.ftp.server.connection.ConnectionLimiter;
import org.example.ftp.server.db.SqliteServerSettingsRepository;
import org.example.ftp.server.http.handler.*;
import org.example.ftp.server.session.ActiveSessionRegistry;
import org.example.ftp.server.stats.StatsService;
import org.example.ftp.server.transfer.RateLimiter;

import java.net.InetSocketAddress;
import java.net.BindException;
import java.util.concurrent.Executors;

public class AdminHttpServer {

    public static void start(
            AuthService authService,
            ConnectionLimiter connectionLimiter,
            RateLimiter uploadRateLimiter,
            RateLimiter downloadRateLimiter,
            StatsService statsService,
            PermissionService permissionService,
            ActiveSessionRegistry sessionRegistry,
            AdminTokenService adminTokenService,

            SqliteUserRepository userRepo,
            SqliteFolderRepository folderRepo,
            SqliteFolderPermissionRepository folderPermRepo,
            SqliteSharedFolderRepository sharedFolderRepo,
            SqliteServerSettingsRepository settingsRepo
    ) throws Exception
    {

        start(
                authService,
                connectionLimiter,
                uploadRateLimiter,
                downloadRateLimiter,
                statsService,
                permissionService,
                sessionRegistry,
                adminTokenService,
                userRepo,
                folderRepo,
                folderPermRepo,
                sharedFolderRepo,
                settingsRepo,
                9090
        );
    }

    public static void start(
            AuthService authService,
            ConnectionLimiter connectionLimiter,
            RateLimiter uploadRateLimiter,
            RateLimiter downloadRateLimiter,
            StatsService statsService,
            PermissionService permissionService,
            ActiveSessionRegistry sessionRegistry,
            AdminTokenService adminTokenService,

            SqliteUserRepository userRepo,
            SqliteFolderRepository folderRepo,
            SqliteFolderPermissionRepository folderPermRepo,
            SqliteSharedFolderRepository sharedFolderRepo,
            SqliteServerSettingsRepository settingsRepo,

            int port
    ) throws Exception {

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (BindException be) {
            throw new BindException("Admin HTTP port " + port + " is already in use. " +
                    "Stop the previous server instance or run with -Dadmin.port=<freePort>.");
        }

        // No-auth endpoint to bootstrap/update admin token in DB
        server.createContext(
                "/api/admin-token",
                new AdminTokenHandler(settingsRepo, adminTokenService)
        );

        // No-auth first-time bootstrap (token + ftp-root path)
        server.createContext(
                "/api/bootstrap",
                new BootstrapHandler(adminTokenService)
        );

        var authFilter = new AuthFilter(adminTokenService);

        server.createContext(
                "/api/users",
                new UsersHandler(authService, permissionService)
        ).getFilters().add(authFilter);

        server.createContext(
                "/api/user-permissions",
                new PermissionsHandler(permissionService)
        ).getFilters().add(authFilter);

        server.createContext(
                "/api/limits",
                new LimitsHandler(connectionLimiter, uploadRateLimiter, downloadRateLimiter, settingsRepo, sessionRegistry)
        ).getFilters().add(authFilter);

        server.createContext(
                "/api/stats",
                new StatsHandler(statsService)
        ).getFilters().add(authFilter);

        server.createContext(
                "/api/stats/live",
                new LiveStatsHandler(statsService, connectionLimiter)
        ).getFilters().add(authFilter);

        server.createContext(
                "/api/folders",
                new FoldersHandler(folderRepo)
        ).getFilters().add(authFilter);

        server.createContext(
                "/api/folders/permissions",
                new FolderPermissionsGetHandler(userRepo, folderRepo, folderPermRepo)
        ).getFilters().add(authFilter);

        server.createContext(
                "/api/folders/permissions/save",
                new FolderPermissionsPostHandler(userRepo, folderRepo, folderPermRepo)
        ).getFilters().add(authFilter);

        server.createContext(
                "/api/shared-folders",
                new SharedFoldersGetHandler(userRepo, sharedFolderRepo)
        ).getFilters().add(authFilter);

        server.createContext(
                "/api/shared-folders/share",
                new SharedFoldersPostHandler(userRepo, sharedFolderRepo)
        ).getFilters().add(authFilter);

        server.createContext(
                "/api/shared-folders/delete",
                new SharedFoldersDeleteHandler(sharedFolderRepo)
        ).getFilters().add(authFilter);

        server.createContext(
                "/api/metrics",
                new MetricsHandler()
        ).getFilters().add(authFilter);

        server.createContext(
                "/api/metrics/reset",
                new MetricsHandler()
        ).getFilters().add(authFilter);

        // Static web admin UI (served from classpath: /admin-ui/*)
        // Longest-prefix match ensures /api/* handlers win over "/".
        server.createContext(
                "/",
                new StaticAdminUiHandler()
        );

        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();

        System.out.println("Admin HTTP API started on port " + port);
    }

}