package al.albus.repository;

import al.albus.config.DatabaseConfig;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class NotificationRepository {

    public List<Map<String, Object>> findForUser(int userId) {
        ensureTable();
        String sql = """
                SELECT id, user_id, trip_id, message, is_read, created_at
                FROM notifications
                WHERE user_id = ?
                ORDER BY created_at DESC
                LIMIT 50
                """;
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getInt("id"));
                row.put("userId", rs.getInt("user_id"));
                row.put("tripId", rs.getObject("trip_id"));
                row.put("message", rs.getString("message"));
                row.put("read", rs.getBoolean("is_read"));
                row.put("createdAt", rs.getString("created_at"));
                rows.add(row);
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return rows;
    }

    public List<Map<String, Object>> findAll() {
        ensureTable();
        String sql = """
                SELECT n.id, n.user_id, n.trip_id, n.message, n.is_read, n.created_at,
                       u.full_name, u.email, u.role
                FROM notifications n
                JOIN users u ON u.id = n.user_id
                ORDER BY n.created_at DESC
                LIMIT 100
                """;
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getInt("id"));
                row.put("userId", rs.getInt("user_id"));
                row.put("tripId", rs.getObject("trip_id"));
                row.put("message", rs.getString("message"));
                row.put("read", rs.getBoolean("is_read"));
                row.put("createdAt", rs.getString("created_at"));
                row.put("fullName", rs.getString("full_name"));
                row.put("email", rs.getString("email"));
                row.put("role", rs.getString("role"));
                rows.add(row);
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return rows;
    }

    public int unreadCount(int userId) {
        ensureTable();
        String sql = "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND is_read = FALSE";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) { e.printStackTrace(); }
        return 0;
    }

    public boolean markRead(int notificationId, int userId) {
        ensureTable();
        String sql = "UPDATE notifications SET is_read = TRUE WHERE id = ? AND user_id = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, notificationId);
            ps.setInt(2, userId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    public boolean markAllRead(int userId) {
        ensureTable();
        String sql = "UPDATE notifications SET is_read = TRUE WHERE user_id = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    public int createForUser(int userId, Integer tripId, String message) {
        ensureTable();
        String sql = "INSERT INTO notifications (user_id, trip_id, message) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, userId);
            if (tripId == null) ps.setNull(2, Types.INTEGER);
            else ps.setInt(2, tripId);
            ps.setString(3, message);
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) return keys.getInt(1);
        } catch (SQLException e) { e.printStackTrace(); }
        return -1;
    }

    private void ensureTable() {
        String sql = """
                CREATE TABLE IF NOT EXISTS notifications (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    user_id INT NOT NULL,
                    trip_id INT NULL,
                    message VARCHAR(500) NOT NULL,
                    is_read BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_notifications_user_id (user_id),
                    INDEX idx_notifications_trip_id (trip_id)
                )
                """;
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }
}
