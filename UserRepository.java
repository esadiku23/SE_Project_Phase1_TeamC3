package al.albus.repository;

import al.albus.config.DatabaseConfig;
import al.albus.model.*;

import java.sql.*;
import java.util.Optional;

public class UserRepository {

    /**
     * Find a user by email + role.
     * Returns the correct subclass so AuthService can cast safely.
     */
    public Optional<User> findByEmail(String email) {
        ensureActiveColumn();
        String sql = "SELECT id, full_name, email, password_hash, role, terminal_id, created_at, is_active " +
                "FROM users WHERE email = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, email);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return Optional.empty();
    }

    public boolean existsByEmail(String email) {
        String sql = "SELECT 1 FROM users WHERE email = ? LIMIT 1";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            ResultSet rs = ps.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public String findTerminalLabelById(Integer terminalId) {
        if (terminalId == null) return "";
        String sql = "SELECT COALESCE(NULLIF(name, ''), city) AS label FROM terminals WHERE id = ? LIMIT 1";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, terminalId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("label");
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return "";
    }

    /**
     * Insert a new passenger (registration flow).
     * Operators and drivers are created by admins, not self-registered.
     */
    public boolean registerPassenger(String fullName, String email, String passwordHash) {
        String sql = "INSERT INTO users (full_name, email, password_hash, role, terminal_id) " +
                "VALUES (?, ?, ?, 'passenger', NULL)";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, fullName);
            ps.setString(2, email);
            ps.setString(3, passwordHash);

            return ps.executeUpdate() == 1;
        } catch (SQLIntegrityConstraintViolationException e) {
            // duplicate email
            System.out.println("Email already registered: " + email);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    // ── private helpers ───────────────────────────────────────
    private User mapRow(ResultSet rs) throws SQLException {
        String role = rs.getString("role");

        User user = switch (role) {
            case "operator" -> new Operator();
            case "driver" -> new al.albus.model.Driver();
            case "admin" -> new User();
            default -> new Passenger();
        };

        user.setId(rs.getInt("id"));
        user.setFullName(rs.getString("full_name"));
        user.setEmail(rs.getString("email"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setRole(role);

        int tid = rs.getInt("terminal_id");
        user.setTerminalId(rs.wasNull() ? null : tid);

        Timestamp ts = rs.getTimestamp("created_at");
        if (ts != null) user.setCreatedAt(ts.toLocalDateTime());
        user.setActive(rs.getBoolean("is_active"));

        return user;
    }

    // ADD this method to UserRepository.java

    /**
     * Update password hash for a user by email.
     */
    public boolean updatePassword(String email, String newHash) {
        String sql = "UPDATE users SET password_hash = ? WHERE email = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newHash);
            ps.setString(2, email);
            return ps.executeUpdate() == 1;
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
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
