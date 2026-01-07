package org.example.ftp.server.db;

public class SqliteServerSettingsRepository {

    public record ServerSettings(
            int globalMaxConnections,
            long globalRateLimit,
            long globalUploadLimit,
            long globalDownloadLimit
    ) {}

    private final Db db;

    public SqliteServerSettingsRepository(Db db) {
        this.db = db;
    }

    public ServerSettings get() {
        // Ensure row exists (id=1)
        db.execute(
                "INSERT OR IGNORE INTO server_settings(id, global_max_connections, global_rate_limit) VALUES (1, 20, 200000)"
        );

        return db.queryOne(
                "SELECT global_max_connections, global_rate_limit, global_upload_limit, global_download_limit FROM server_settings WHERE id = 1",
                rs -> new ServerSettings(
                        rs.getInt("global_max_connections"),
                        rs.getLong("global_rate_limit"),
                        rs.getLong("global_upload_limit"),
                        rs.getLong("global_download_limit")
                )
        );
    }

    public String getAdminToken() {
        // Ensure row exists (id=1)
        db.execute(
                "INSERT OR IGNORE INTO server_settings(id, global_max_connections, global_rate_limit) VALUES (1, 20, 200000)"
        );

        return db.queryOne(
                "SELECT admin_token FROM server_settings WHERE id = 1",
                rs -> rs.getString("admin_token")
        );
    }

    public void saveAdminToken(String token) {
        // Ensure row exists
        db.execute(
                "INSERT OR IGNORE INTO server_settings(id, global_max_connections, global_rate_limit) VALUES (1, 20, 200000)"
        );
        db.execute(
                "UPDATE server_settings SET admin_token = ? WHERE id = 1",
                token
        );
    }

    /**
     * Backwards-compatible save:
     * - If globalRateLimit is provided, we also set global_upload_limit and global_download_limit to the same value.
     * - If globalUploadLimit/globalDownloadLimit are provided, they override their respective columns.
     */
    public void save(Integer globalMaxConnections, Long globalRateLimit) {
        save(globalMaxConnections, globalRateLimit, null, null);
    }

    public void save(
            Integer globalMaxConnections,
            Long globalRateLimit,
            Long globalUploadLimit,
            Long globalDownloadLimit
    ) {
        // Ensure row exists
        db.execute(
                "INSERT OR IGNORE INTO server_settings(id, global_max_connections, global_rate_limit) VALUES (1, 20, 200000)"
        );

        if (globalMaxConnections != null) {
            db.execute(
                    "UPDATE server_settings SET global_max_connections = ? WHERE id = 1",
                    globalMaxConnections
            );
        }

        if (globalRateLimit != null) {
            db.execute(
                    "UPDATE server_settings SET global_rate_limit = ? WHERE id = 1",
                    globalRateLimit
            );
            // Keep new columns in sync for legacy clients
            db.execute(
                    "UPDATE server_settings SET global_upload_limit = ? WHERE id = 1",
                    globalRateLimit
            );
            db.execute(
                    "UPDATE server_settings SET global_download_limit = ? WHERE id = 1",
                    globalRateLimit
            );
        }

        if (globalUploadLimit != null) {
            db.execute(
                    "UPDATE server_settings SET global_upload_limit = ? WHERE id = 1",
                    globalUploadLimit
            );
        }

        if (globalDownloadLimit != null) {
            db.execute(
                    "UPDATE server_settings SET global_download_limit = ? WHERE id = 1",
                    globalDownloadLimit
            );
        }
    }
}


