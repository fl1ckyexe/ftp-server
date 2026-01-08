import java.sql.*;
import java.nio.file.*;

public class ViewDb {
    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        Path dbPath = Path.of("ftp-root", "ftp.db");
        String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        
        try (Connection conn = DriverManager.getConnection(url);
             Statement st = conn.createStatement()) {
            
            System.out.println("=== USERS ===");
            try (ResultSet rs = st.executeQuery("SELECT id, username, enabled FROM users ORDER BY username")) {
                boolean hasData = false;
                while (rs.next()) {
                    hasData = true;
                    System.out.printf("id=%d, username=%s, enabled=%d%n",
                        rs.getInt("id"), rs.getString("username"), rs.getInt("enabled"));
                }
                if (!hasData) System.out.println("(no users)");
            }
            
            System.out.println("\n=== PERMISSIONS (global r/w/e) ===");
            try (ResultSet rs = st.executeQuery(
                "SELECT u.username, p.r, p.w, p.e FROM permissions p JOIN users u ON p.user_id = u.id ORDER BY u.username")) {
                boolean hasData = false;
                while (rs.next()) {
                    hasData = true;
                    System.out.printf("user=%s, r=%d, w=%d, e=%d%n",
                        rs.getString("username"),
                        rs.getInt("r"), rs.getInt("w"), rs.getInt("e"));
                }
                if (!hasData) System.out.println("(no permissions rows)");
            }
            
            System.out.println("\n=== SHARED FOLDERS ===");
            try (ResultSet rs = st.executeQuery(
                "SELECT o.username AS owner, s.username AS shared_to, sf.folder_name, sf.folder_path, sf.r, sf.w, sf.e " +
                "FROM shared_folders sf " +
                "JOIN users o ON sf.owner_user_id = o.id " +
                "JOIN users s ON sf.user_to_share_id = s.id " +
                "ORDER BY o.username, s.username")) {
                boolean hasData = false;
                while (rs.next()) {
                    hasData = true;
                    System.out.printf("owner=%s -> shared_to=%s, path=%s, name=%s, r=%d, w=%d, e=%d%n",
                        rs.getString("owner"),
                        rs.getString("shared_to"),
                        rs.getString("folder_path"),
                        rs.getString("folder_name"),
                        rs.getInt("r"), rs.getInt("w"), rs.getInt("e"));
                }
                if (!hasData) System.out.println("(no shared folders)");
            }
            
            System.out.println("\n=== USERS WITHOUT PERMISSIONS ROW ===");
            try (ResultSet rs = st.executeQuery(
                "SELECT u.id, u.username FROM users u LEFT JOIN permissions p ON u.id = p.user_id WHERE p.user_id IS NULL")) {
                boolean hasData = false;
                while (rs.next()) {
                    hasData = true;
                    System.out.printf("id=%d, username=%s (MISSING permissions row!)%n",
                        rs.getInt("id"), rs.getString("username"));
                }
                if (!hasData) System.out.println("(all users have permissions)");
            }
            
            System.out.println("\n=== ADMIN TOKEN ===");
            try (ResultSet rs = st.executeQuery("SELECT admin_token FROM server_settings WHERE id = 1")) {
                if (rs.next()) {
                    String token = rs.getString("admin_token");
                    System.out.println("admin_token=" + (token == null || token.isBlank() ? "(NOT SET)" : token));
                } else {
                    System.out.println("(no server_settings row)");
                }
            }
        }
    }
}
