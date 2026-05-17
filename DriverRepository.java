package al.albus.repository;

import al.albus.config.DatabaseConfig;
import al.albus.model.Driver;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class DriverRepository {

    /** All drivers assigned to a terminal. */
    public List<Driver> findByTerminal(int terminalId) {
        ensureActiveColumn();
        String sql = "SELECT id, full_name, email, role, terminal_id, created_at " +
                "FROM users WHERE role='driver' AND terminal_id=? AND is_active = TRUE ORDER BY full_name";
        List<Driver> list = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, terminalId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Driver d = new Driver();
                d.setId(rs.getInt("id"));
                d.setFullName(rs.getString("full_name"));
                d.setEmail(rs.getString("email"));
                d.setRole("driver");
                d.setTerminalId(rs.getInt("terminal_id"));
                Timestamp ts = rs.getTimestamp("created_at");
                if (ts != null) d.setCreatedAt(ts.toLocalDateTime());
                list.add(d);
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    /** Create a new driver account (operator action). */
    public boolean createDriver(String fullName, String email, String passwordHash, int terminalId) {
        ensureActiveColumn();
        String sql = "INSERT INTO users (full_name, email, password_hash, role, terminal_id) " +
                "VALUES (?, ?, ?, 'driver', ?)";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fullName);
            ps.setString(2, email);
            ps.setString(3, passwordHash);
            ps.setInt(4, terminalId);
            return ps.executeUpdate() == 1;
        } catch (SQLIntegrityConstraintViolationException e) {
            System.out.println("Email already registered: " + email);
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    private void ensureActiveColumn() {
        String checkSql = """
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'users'
                  AND column_name = 'is_active'
                """;
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(checkSql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next() && rs.getInt(1) > 0) return;
            try (PreparedStatement alter = conn.prepareStatement(
                    "ALTER TABLE users ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE")) {
                alter.executeUpdate();
            }
        } catch (SQLException e) { e.printStackTrace(); }
    }

    /** Count active trips for a driver (to prevent double-booking). */
    public int countActiveTrips(int driverId) {
        String sql = "SELECT COUNT(*) FROM trips WHERE driver_id=? AND LOWER(status) IN ('scheduled','departed')";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, driverId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) { e.printStackTrace(); }
        return 0;
    }
}
