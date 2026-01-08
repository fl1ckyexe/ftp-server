package org.example.ftp.server.auth.db;

import org.example.ftp.server.auth.Permission;
import org.example.ftp.server.auth.model.SharedFolder;
import org.example.ftp.server.db.Db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class SqliteSharedFolderRepository {

    private final Db db;

    public SqliteSharedFolderRepository(Db db) {
        this.db = db;
    }

    public List<SharedFolder> findAll() {
        String sql = """
            SELECT id, owner_user_id, user_to_share_id, folder_name, folder_path, r, w, e
            FROM shared_folders
            ORDER BY folder_path
            """;

        List<SharedFolder> out = new ArrayList<>();

        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                out.add(map(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return out;
    }

    public List<SharedFolder> findByUserToShare(long userToShareId) {
        String sql = """
            SELECT id, owner_user_id, user_to_share_id, folder_name, folder_path, r, w, e
            FROM shared_folders
            WHERE user_to_share_id = ?
            ORDER BY folder_path
            """;

        List<SharedFolder> out = new ArrayList<>();

        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {

            ps.setLong(1, userToShareId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return out;
    }

    public void create(long ownerUserId, long userToShareId, String folderName, String folderPath, boolean read, boolean write, boolean execute) {
        String sql = """
            INSERT INTO shared_folders(owner_user_id, user_to_share_id, folder_name, folder_path, r, w, e)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {

            ps.setLong(1, ownerUserId);
            ps.setLong(2, userToShareId);
            ps.setString(3, folderName);
            ps.setString(4, folderPath);
            ps.setInt(5, read ? 1 : 0);
            ps.setInt(6, write ? 1 : 0);
            ps.setInt(7, execute ? 1 : 0);

            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean exists(long ownerUserId, long userToShareId, String folderPath) {
        String sql = """
            SELECT COUNT(*) FROM shared_folders
            WHERE owner_user_id = ? AND user_to_share_id = ? AND folder_path = ?
            """;

        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {

            ps.setLong(1, ownerUserId);
            ps.setLong(2, userToShareId);
            ps.setString(3, folderPath);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return false;
    }
    
    /**
     * Проверяет, есть ли у пользователя доступ к указанному пути через shared folders.
     * Поддерживает доступ к подпапкам (если папка /admin/test поделена, доступ есть и к /admin/test/subfolder).
     * 
     * @param userToShareId ID пользователя, которому предоставлен доступ
     * @param folderPath Путь для проверки (например, /admin/lasttest или /admin/lasttest/subfolder)
     * @return true, если есть доступ, false иначе
     */
    public boolean hasAccess(long userToShareId, String folderPath) {
        String sql = """
            SELECT COUNT(*) FROM shared_folders
            WHERE user_to_share_id = ? 
            AND (folder_path = ? OR (? LIKE folder_path || '/%'))
            """;

        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {

            ps.setLong(1, userToShareId);
            ps.setString(2, folderPath);
            ps.setString(3, folderPath);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return false;
    }

    /**
     * Checks whether the user has the requested permission for an FTP-style absolute path.
     * Supports subfolders: if /owner/test is shared, it applies to /owner/test/sub.
     *
     * @param userToShareId user id that receives the share
     * @param folderPath FTP-style absolute path ("/owner/path")
     */
    public boolean hasPermission(long userToShareId, String folderPath, Permission permission) {
        String sql = """
            SELECT r, w, e, LENGTH(folder_path) AS len
            FROM shared_folders
            WHERE user_to_share_id = ?
              AND (folder_path = ? OR (? LIKE folder_path || '/%'))
            ORDER BY len DESC
            LIMIT 1
            """;

        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {

            ps.setLong(1, userToShareId);
            ps.setString(2, folderPath);
            ps.setString(3, folderPath);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }

                return switch (permission) {
                    case READ -> rs.getInt("r") == 1;
                    case WRITE -> rs.getInt("w") == 1;
                    case EXECUTE -> rs.getInt("e") == 1;
                };
            }

        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    /**
     * Находит owner_user_id для указанного пути папки (берет первую найденную запись).
     * 
     * @param folderPath Путь папки (например, /admin/testdir)
     * @return owner_user_id или null, если папка не найдена
     */
    public Long findOwnerByFolderPath(String folderPath) {
        String sql = """
            SELECT owner_user_id FROM shared_folders
            WHERE folder_path = ?
            LIMIT 1
            """;
        
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            
            ps.setString(1, folderPath);
            
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("owner_user_id");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        
        return null;
    }
    
    /**
     * Удаляет запись shared folder по ID.
     * 
     * @param id ID записи для удаления
     */
    public void deleteById(long id) {
        String sql = "DELETE FROM shared_folders WHERE id = ?";
        
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Удаляет все записи shared folders для указанного пути папки.
     * Удаляет записи, где folder_path совпадает с указанным путем или является его родительским путём.
     * 
     * @param folderPath Путь папки для удаления (например, /admin/testdir)
     */
    public void deleteByFolderPath(String folderPath) {
        String sql = """
            DELETE FROM shared_folders
            WHERE folder_path = ? OR folder_path LIKE ? || '/%'
            """;
        
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            
            ps.setString(1, folderPath);
            ps.setString(2, folderPath);
            
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private SharedFolder map(ResultSet rs) throws SQLException {
        return new SharedFolder(
            rs.getLong("id"),
            rs.getLong("owner_user_id"),
            rs.getLong("user_to_share_id"),
            rs.getString("folder_name"),
            rs.getString("folder_path"),
            rs.getInt("r") == 1,
            rs.getInt("w") == 1,
            rs.getInt("e") == 1
        );
    }
}

