package org.example.ftp.server.db;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class Db {

    private final String url;

    public Db(Path dbFile) {
        this.url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
    }

    public Connection getConnection() throws Exception {
        Connection conn = DriverManager.getConnection(url);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
        }
        return conn;
    }

    public void initSchema() {
        // Сначала выполняем миграции для существующих таблиц
        // Миграция: добавляем колонки r, w, e если таблица shared_folders уже существует без них
        try (Connection c = getConnection();
             Statement st = c.createStatement()) {
            // Пробуем добавить колонки, если их нет (будет ошибка если уже есть, игнорируем)
            try {
                st.execute("ALTER TABLE shared_folders ADD COLUMN r INTEGER NOT NULL DEFAULT 1");
            } catch (SQLException e) {
                // Колонка уже существует, игнорируем
            }
            try {
                st.execute("ALTER TABLE shared_folders ADD COLUMN w INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException e) {
                // Колонка уже существует, игнорируем
            }
            try {
                st.execute("ALTER TABLE shared_folders ADD COLUMN e INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException e) {
                // Колонка уже существует, игнорируем
            }
        } catch (Exception e) {
            // Игнорируем ошибки миграции (таблица может не существовать)
        }
        
        // Миграция: добавляем колонки upload_speed и download_speed в users
        // Если rate_limit существует и не null, копируем его значение в upload_speed и download_speed
        try (Connection c = getConnection();
             Statement st = c.createStatement()) {
            try {
                st.execute("ALTER TABLE users ADD COLUMN upload_speed INTEGER");
            } catch (SQLException e) {
                // Колонка уже существует, игнорируем
            }
            try {
                st.execute("ALTER TABLE users ADD COLUMN download_speed INTEGER");
            } catch (SQLException e) {
                // Колонка уже существует, игнорируем
            }
            // Копируем rate_limit в upload_speed и download_speed, если они еще не установлены
            try {
                st.execute("UPDATE users SET upload_speed = rate_limit WHERE upload_speed IS NULL AND rate_limit IS NOT NULL AND rate_limit > 0");
                st.execute("UPDATE users SET download_speed = rate_limit WHERE download_speed IS NULL AND rate_limit IS NOT NULL AND rate_limit > 0");
            } catch (SQLException e) {
                // Игнорируем ошибки
            }
        } catch (Exception e) {
            // Игнорируем ошибки миграции (таблица может не существовать)
        }
        
        // Миграция: добавляем колонки global_upload_limit и global_download_limit в server_settings
        try (Connection c = getConnection();
             Statement st = c.createStatement()) {
            // Добавляем колонки как nullable (SQLite не позволяет NOT NULL для существующих таблиц)
            try {
                st.execute("ALTER TABLE server_settings ADD COLUMN global_upload_limit INTEGER");
                // Обновляем существующие записи значением по умолчанию
                st.execute("UPDATE server_settings SET global_upload_limit = 200000 WHERE global_upload_limit IS NULL");
            } catch (SQLException e) {
                // Колонка уже существует, проверяем и обновляем если нужно
                try {
                    st.execute("UPDATE server_settings SET global_upload_limit = 200000 WHERE global_upload_limit IS NULL");
                } catch (SQLException ignored) {
                    // Игнорируем если уже обновлено
                }
            }
            try {
                st.execute("ALTER TABLE server_settings ADD COLUMN global_download_limit INTEGER");
                // Обновляем существующие записи значением по умолчанию
                st.execute("UPDATE server_settings SET global_download_limit = 200000 WHERE global_download_limit IS NULL");
            } catch (SQLException e) {
                // Колонка уже существует, проверяем и обновляем если нужно
                try {
                    st.execute("UPDATE server_settings SET global_download_limit = 200000 WHERE global_download_limit IS NULL");
                } catch (SQLException ignored) {
                    // Игнорируем если уже обновлено
                }
            }
        } catch (Exception e) {
            // Игнорируем ошибки миграции (таблица может не существовать)
        }

        // Миграция: admin_token в server_settings (plain text; no security as requested)
        try (Connection c = getConnection();
             Statement st = c.createStatement()) {
            try {
                st.execute("ALTER TABLE server_settings ADD COLUMN admin_token TEXT");
            } catch (SQLException e) {
                // already exists
            }
        } catch (Exception e) {
            // ignore
        }
        
        // Миграция: создаем записи permissions для всех пользователей, у которых их нет
        // Это важно для работы глобальных прав
        try (Connection c = getConnection();
             Statement st = c.createStatement()) {
            try {
                st.execute("""
                    INSERT INTO permissions(user_id, r, w, e)
                    SELECT u.id, 1, 1, 1
                    FROM users u
                    WHERE NOT EXISTS (
                        SELECT 1 FROM permissions p WHERE p.user_id = u.id
                    )
                    """);
            } catch (SQLException e) {
                // Игнорируем ошибки (таблица может не существовать или уже обновлено)
            }
        } catch (Exception e) {
            // Игнорируем ошибки миграции
        }
        
        // Теперь выполняем основной SQL скрипт для создания таблиц
        String sql = """
            CREATE TABLE IF NOT EXISTS users (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              username TEXT NOT NULL UNIQUE,
              password_hash TEXT NOT NULL,
              enabled INTEGER NOT NULL DEFAULT 1,
              rate_limit INTEGER,
              upload_speed INTEGER,
              download_speed INTEGER
            );

            CREATE TABLE IF NOT EXISTS permissions (
              user_id INTEGER PRIMARY KEY,
              r INTEGER NOT NULL DEFAULT 1,
              w INTEGER NOT NULL DEFAULT 1,
              e INTEGER NOT NULL DEFAULT 1,
              FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS stats (
              user_id INTEGER PRIMARY KEY,
              logins INTEGER NOT NULL DEFAULT 0,
              bytes_uploaded INTEGER NOT NULL DEFAULT 0,
              bytes_downloaded INTEGER NOT NULL DEFAULT 0,
              last_login TEXT,
              FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS folders (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              path TEXT NOT NULL UNIQUE,
              owner_user_id INTEGER,
              is_global INTEGER NOT NULL DEFAULT 1,
              FOREIGN KEY (owner_user_id) REFERENCES users(id) ON DELETE SET NULL
            );

            CREATE TABLE IF NOT EXISTS folder_permissions (
              user_id INTEGER NOT NULL,
              folder_id INTEGER NOT NULL,
              r INTEGER NOT NULL DEFAULT 0,
              w INTEGER NOT NULL DEFAULT 0,
              e INTEGER NOT NULL DEFAULT 0,
              PRIMARY KEY (user_id, folder_id),
              FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
              FOREIGN KEY (folder_id) REFERENCES folders(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS shared_folders (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              owner_user_id INTEGER NOT NULL,
              user_to_share_id INTEGER NOT NULL,
              folder_name TEXT NOT NULL,
              folder_path TEXT NOT NULL,
              r INTEGER NOT NULL DEFAULT 1,
              w INTEGER NOT NULL DEFAULT 0,
              e INTEGER NOT NULL DEFAULT 0,
              FOREIGN KEY (owner_user_id) REFERENCES users(id) ON DELETE CASCADE,
              FOREIGN KEY (user_to_share_id) REFERENCES users(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS server_settings (
              id INTEGER PRIMARY KEY CHECK (id = 1),
              global_max_connections INTEGER NOT NULL DEFAULT 20,
              global_rate_limit INTEGER NOT NULL DEFAULT 200000,
              global_upload_limit INTEGER NOT NULL DEFAULT 200000,
              global_download_limit INTEGER NOT NULL DEFAULT 200000,
              admin_token TEXT
            );

            INSERT OR IGNORE INTO server_settings(id, global_max_connections, global_rate_limit, global_upload_limit, global_download_limit)
            VALUES (1, 20, 200000, 200000, 200000);

            INSERT OR IGNORE INTO folders(path, owner_user_id, is_global) VALUES ('/', NULL, 1);
            INSERT OR IGNORE INTO folders(path, owner_user_id, is_global) VALUES ('/shared', NULL, 1);
            """;

        try (Connection c = getConnection();
             Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        } catch (Exception e) {
            throw new RuntimeException("Failed to init database", e);
        }
    }

    public int execute(String sql, Object... params) {
        try (
                var conn = getConnection();
                var ps = conn.prepareStatement(sql)
        ) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("DB execute failed", e);
        }
    }

    public <T> T queryOne(String sql, ResultSetMapper<T> mapper, Object... params) {
        try (
                var conn = getConnection();
                var ps = conn.prepareStatement(sql)
        ) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }

            try (var rs = ps.executeQuery()) {
                if (rs.next()) return mapper.map(rs);
                return null;
            }
        } catch (Exception e) {
            throw new RuntimeException("DB queryOne failed", e);
        }
    }
}