package al.albus.repository;

import al.albus.config.DatabaseConfig;
import al.albus.model.User;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class AdminRepository {

    // ─────────────────────────────────────────────
    //  Schema bootstrap
    // ─────────────────────────────────────────────

    /** Adds is_active to users if an old deployment is missing it. */
    private void ensureAdminSchema() {
        ensureColumn("users", "is_active",
                "ALTER TABLE users ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE");
    }

    private void ensureColumn(String table, String column, String alterSql) {
        String checkSql = """
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name   = ?
                  AND column_name  = ?
                """;
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getInt(1) > 0) return;          // column already present
            try (PreparedStatement alter = conn.prepareStatement(alterSql)) {
                alter.executeUpdate();
            }
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private void ensureNotificationsTable() {
        String sql = """
                CREATE TABLE IF NOT EXISTS notifications (
                    id         INT AUTO_INCREMENT PRIMARY KEY,
                    user_id    INT          NOT NULL,
                    trip_id    INT          NULL,
                    message    VARCHAR(500) NOT NULL,
                    is_read    BOOLEAN      NOT NULL DEFAULT FALSE,
                    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_notifications_user_id (user_id),
                    INDEX idx_notifications_trip_id (trip_id)
                )
                """;
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private void ensureTerminalCapacityTable() {
        // Table already exists in production schema, but guard for clean installs.
        String createSql = """
                CREATE TABLE IF NOT EXISTS terminal_capacity (
                    id           INT AUTO_INCREMENT PRIMARY KEY,
                    terminal_id  INT NOT NULL UNIQUE,
                    max_buses_day INT NOT NULL DEFAULT 20
                )
                """;
        String seedSql = """
                INSERT IGNORE INTO terminal_capacity (terminal_id, max_buses_day)
                SELECT t.id, 20
                FROM terminals t
                LEFT JOIN terminal_capacity tc ON tc.terminal_id = t.id
                WHERE tc.terminal_id IS NULL
                """;
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement create = conn.prepareStatement(createSql);
             PreparedStatement seed   = conn.prepareStatement(seedSql)) {
            create.executeUpdate();
            seed.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    // ─────────────────────────────────────────────
    //  User queries
    // ─────────────────────────────────────────────

    /**
     * Returns staff accounts only (operator + driver), optionally filtered by role.
     * Passengers are never returned from this method — admin does not manage them.
     */
    public List<User> findStaff(String role) {
        ensureAdminSchema();
        boolean filterByRole = role != null && !role.isBlank()
                && !"all".equalsIgnoreCase(role)
                && !"passenger".equalsIgnoreCase(role);

        String sql = "SELECT id, full_name, email, role, terminal_id, created_at, is_active "
                + "FROM users "
                + "WHERE role IN ('operator','driver') "
                + (filterByRole ? "AND role = ? " : "")
                + "ORDER BY role, full_name";

        List<User> users = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (filterByRole) ps.setString(1, role);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) users.add(mapUser(rs));
        } catch (SQLException e) { e.printStackTrace(); }
        return users;
    }

    public Optional<User> findUserById(int id) {
        ensureAdminSchema();
        String sql = "SELECT id, full_name, email, role, terminal_id, created_at, is_active "
                + "FROM users WHERE id = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return Optional.of(mapUser(rs));
        } catch (SQLException e) { e.printStackTrace(); }
        return Optional.empty();
    }

    /**
     * Sets is_active = FALSE for the given user.
     * Only succeeds if the user has role operator or driver (extra DB-level guard).
     */
    public boolean deactivateUser(int id) {
        ensureAdminSchema();
        String sql = "UPDATE users SET is_active = FALSE "
                + "WHERE id = ? AND role IN ('operator','driver')";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    // ─────────────────────────────────────────────
    //  Terminal queries
    // ─────────────────────────────────────────────

    public List<Map<String, Object>> findTerminals() {
        String sql = "SELECT id, city, name FROM terminals ORDER BY city";
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",   rs.getInt("id"));
                row.put("city", rs.getString("city"));
                row.put("name", rs.getString("name"));
                list.add(row);
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    public List<Map<String, Object>> terminalOverview() {
        String sql = """
                SELECT t.id, t.city, t.name,
                       COALESCE(dest.destinations, '') AS destinations,
                       COALESCE(ops.operator_count, 0) AS operator_count,
                       COALESCE(drv.driver_count, 0) AS driver_count,
                       COALESCE(bus.bus_count, 0) AS bus_count
                FROM terminals t
                LEFT JOIN (
                    SELECT r.origin_id,
                           GROUP_CONCAT(DISTINCT COALESCE(NULLIF(td.name, ''), td.city)
                                        ORDER BY COALESCE(NULLIF(td.name, ''), td.city)
                                        SEPARATOR ', ') AS destinations
                    FROM routes r
                    JOIN terminals td ON td.id = r.destination_id
                    GROUP BY r.origin_id
                ) dest ON dest.origin_id = t.id
                LEFT JOIN (
                    SELECT terminal_id, COUNT(*) AS operator_count
                    FROM users
                    WHERE role = 'operator'
                      AND is_active = TRUE
                    GROUP BY terminal_id
                ) ops ON ops.terminal_id = t.id
                LEFT JOIN (
                    SELECT terminal_id, COUNT(*) AS driver_count
                    FROM users
                    WHERE role = 'driver'
                      AND is_active = TRUE
                    GROUP BY terminal_id
                ) drv ON drv.terminal_id = t.id
                LEFT JOIN (
                    SELECT terminal_id, COUNT(*) AS bus_count
                    FROM buses
                    WHERE is_active = TRUE
                    GROUP BY terminal_id
                ) bus ON bus.terminal_id = t.id
                ORDER BY t.city, t.name
                """;
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("terminalId", rs.getInt("id"));
                row.put("city", rs.getString("city"));
                row.put("name", rs.getString("name"));
                row.put("destinations", rs.getString("destinations"));
                row.put("operatorCount", rs.getInt("operator_count"));
                row.put("driverCount", rs.getInt("driver_count"));
                row.put("busCount", rs.getInt("bus_count"));
                rows.add(row);
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return rows;
    }

    public boolean terminalExists(int terminalId) {
        String sql = "SELECT 1 FROM terminals WHERE id = ? LIMIT 1";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, terminalId);
            return ps.executeQuery().next();
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    // ─────────────────────────────────────────────
    //  Email uniqueness check
    // ─────────────────────────────────────────────

    public boolean emailExists(String email) {
        String sql = "SELECT 1 FROM users WHERE LOWER(email) = LOWER(?) LIMIT 1";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            return ps.executeQuery().next();
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    // ─────────────────────────────────────────────
    //  Terminal capacity
    // ─────────────────────────────────────────────

    public List<Map<String, Object>> terminalCapacity() {
        ensureTerminalCapacityTable();
        String sql = """
                SELECT t.id AS terminal_id, t.city, t.name,
                       COALESCE(tc.max_buses_day, 20) AS max_buses_day
                FROM terminals t
                LEFT JOIN terminal_capacity tc ON tc.terminal_id = t.id
                ORDER BY t.city
                """;
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("terminalId",  rs.getInt("terminal_id"));
                row.put("city",        rs.getString("city"));
                row.put("name",        rs.getString("name"));
                row.put("maxBusesDay", rs.getInt("max_buses_day"));
                rows.add(row);
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return rows;
    }

    /**
     * Upserts the max_buses_day for a terminal.
     * Uses INSERT … ON DUPLICATE KEY UPDATE to handle the UNIQUE constraint on terminal_id.
     */
    public boolean updateTerminalCapacity(int terminalId, int maxBusesDay) {
        ensureTerminalCapacityTable();
        String sql = """
                INSERT INTO terminal_capacity (terminal_id, max_buses_day)
                VALUES (?, ?)
                ON DUPLICATE KEY UPDATE max_buses_day = VALUES(max_buses_day)
                """;
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, terminalId);
            ps.setInt(2, maxBusesDay);
            return ps.executeUpdate() >= 1;   // 1 = insert, 2 = update (MySQL convention)
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    /**
     * Checks whether the terminal still has capacity for one more bus on tripDate.
     * Pass excludeTripId when editing an existing trip so it is not counted against itself.
     */
    public boolean hasTerminalCapacity(int terminalId, String tripDate) {
        return hasTerminalCapacity(terminalId, tripDate, null);
    }

    public boolean hasTerminalCapacity(int terminalId, String tripDate, Integer excludeTripId) {
        ensureTerminalCapacityTable();
        String sql = """
                SELECT COALESCE(tc.max_buses_day, 0) AS max_buses_day,
                       COUNT(tr.id)                   AS scheduled_buses
                FROM terminals t
                LEFT JOIN terminal_capacity tc ON tc.terminal_id = t.id
                LEFT JOIN trips tr
                       ON tr.origin_terminal_id = t.id
                      AND DATE(tr.departure_time) = ?
                      AND LOWER(tr.status) <> 'cancelled'
                      AND (? IS NULL OR tr.id <> ?)
                WHERE t.id = ?
                GROUP BY tc.max_buses_day
                """;
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tripDate);
            if (excludeTripId == null) {
                ps.setNull(2, Types.INTEGER);
                ps.setNull(3, Types.INTEGER);
            } else {
                ps.setInt(2, excludeTripId);
                ps.setInt(3, excludeTripId);
            }
            ps.setInt(4, terminalId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return false;
            int maxBusesDay    = rs.getInt("max_buses_day");
            int scheduledBuses = rs.getInt("scheduled_buses");
            return maxBusesDay <= 0 || scheduledBuses < maxBusesDay;
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    // ─────────────────────────────────────────────
    //  Staff creation
    // ─────────────────────────────────────────────

    public boolean createOperator(String fullName, String email, String passwordHash, int terminalId) {
        return createStaff(fullName, email, passwordHash, "operator", terminalId);
    }

    public boolean createDriver(String fullName, String email, String passwordHash, int terminalId) {
        return createStaff(fullName, email, passwordHash, "driver", terminalId);
    }

    private boolean createStaff(String fullName, String email, String passwordHash,
                                String role, int terminalId) {
        ensureAdminSchema();
        String sql = "INSERT INTO users "
                + "(full_name, email, password_hash, role, terminal_id, is_active) "
                + "VALUES (?, ?, ?, ?, ?, TRUE)";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fullName);
            ps.setString(2, email);
            ps.setString(3, passwordHash);
            ps.setString(4, role);
            ps.setInt(5, terminalId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    // ─────────────────────────────────────────────
    //  Statistics
    // ─────────────────────────────────────────────

    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("admins",       countWhere("users", "role = 'admin'"));
        stats.put("operators",    countWhere("users", "role = 'operator'"));
        stats.put("drivers",      countWhere("users", "role = 'driver'"));
        stats.put("passengers",   countWhere("users", "role = 'passenger'"));
        stats.put("routes",       countWhere("routes", null));
        stats.put("trips",        countWhere("trips",  null));
        stats.put("bookings",     countWhere("bookings", null));
        stats.put("totalRevenue", totalRevenue());
        stats.put("terminalStats", terminalStats());
        return stats;
    }

    private BigDecimal totalRevenue() {
        String sql = "SELECT COALESCE(SUM(pd.final_price), 0) FROM passengers_detail pd";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getBigDecimal(1);
        } catch (SQLException e) { e.printStackTrace(); }
        return BigDecimal.ZERO;
    }

    private List<Map<String, Object>> terminalStats() {
        String sql = """
                SELECT t.id, t.city, t.name,
                       COUNT(DISTINCT tr.id)  AS total_trips,
                       COUNT(DISTINCT b.id)   AS total_bookings,
                       COALESCE(SUM(pd.final_price), 0) AS total_revenue
                FROM terminals t
                LEFT JOIN trips tr            ON tr.origin_terminal_id = t.id
                LEFT JOIN bookings b          ON b.trip_id = tr.id
                LEFT JOIN passengers_detail pd ON pd.booking_id = b.id
                GROUP BY t.id, t.city, t.name
                ORDER BY t.city
                """;
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("terminalId",    rs.getInt("id"));
                row.put("city",          rs.getString("city"));
                row.put("name",          rs.getString("name"));
                row.put("totalTrips",    rs.getInt("total_trips"));
                row.put("totalBookings", rs.getInt("total_bookings"));
                row.put("totalRevenue",  rs.getBigDecimal("total_revenue"));
                rows.add(row);
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return rows;
    }

    private int countWhere(String table, String where) {
        String sql = "SELECT COUNT(*) FROM " + table
                + (where != null ? " WHERE " + where : "");
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) { e.printStackTrace(); }
        return 0;
    }

    // ─────────────────────────────────────────────
    //  Notifications
    // ─────────────────────────────────────────────

    /**
     * Inserts a notification row for every recipient of the given trip:
     * the assigned driver + the operator(s) of the origin terminal.
     *
     * @return number of rows inserted
     */
    public int notifyTripChanged(int tripId, String message) {
        ensureNotificationsTable();
        String sql = """
                INSERT INTO notifications (user_id, trip_id, message)
                SELECT recipient_id, ?, ?
                FROM (
                    SELECT driver_id AS recipient_id
                    FROM   trips
                    WHERE  id = ?
                      AND  driver_id IS NOT NULL

                    UNION

                    SELECT u.id AS recipient_id
                    FROM   trips t
                    JOIN   users u
                           ON  u.terminal_id = t.origin_terminal_id
                           AND u.role        = 'operator'
                           AND u.is_active   = TRUE
                    WHERE  t.id = ?
                ) AS recipients
                WHERE recipient_id IS NOT NULL
                """;
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ps.setString(2, message);
            ps.setInt(3, tripId);
            ps.setInt(4, tripId);
            return ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
        return 0;
    }

    public int notifyTripChanged(int tripId, Integer previousDriverId, Integer previousTerminalId, String message) {
        ensureNotificationsTable();
        String sql = """
                INSERT INTO notifications (user_id, trip_id, message)
                SELECT DISTINCT recipient_id, ?, ?
                FROM (
                    SELECT driver_id AS recipient_id
                    FROM   trips
                    WHERE  id = ?
                      AND  driver_id IS NOT NULL

                    UNION

                    SELECT u.id AS recipient_id
                    FROM   trips t
                    JOIN   users u
                           ON  u.terminal_id = t.origin_terminal_id
                           AND u.role        = 'operator'
                           AND u.is_active   = TRUE
                    WHERE  t.id = ?

                    UNION

                    SELECT ? AS recipient_id

                    UNION

                    SELECT u.id AS recipient_id
                    FROM   users u
                    WHERE  u.terminal_id = ?
                      AND  u.role        = 'operator'
                      AND  u.is_active   = TRUE
                ) AS recipients
                WHERE recipient_id IS NOT NULL
                """;
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ps.setString(2, message);
            ps.setInt(3, tripId);
            ps.setInt(4, tripId);
            if (previousDriverId == null) ps.setNull(5, Types.INTEGER);
            else ps.setInt(5, previousDriverId);
            if (previousTerminalId == null) ps.setNull(6, Types.INTEGER);
            else ps.setInt(6, previousTerminalId);
            return ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
        return 0;
    }

    public int notifyTerminalStaff(int terminalId, String message) {
        ensureNotificationsTable();
        String sql = """
                INSERT INTO notifications (user_id, trip_id, message)
                SELECT u.id, NULL, ?
                FROM users u
                WHERE u.terminal_id = ?
                  AND u.role = 'operator'
                  AND u.is_active = TRUE
                """;
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, message);
            ps.setInt(2, terminalId);
            return ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
        return 0;
    }

    public int notifyPassengersForTrip(int tripId, String message) {
        ensureNotificationsTable();
        String sql = """
                INSERT INTO notifications (user_id, trip_id, message)
                SELECT DISTINCT b.passenger_id, ?, ?
                FROM bookings b
                WHERE b.trip_id = ?
                  AND LOWER(COALESCE(b.payment_status, '')) <> 'cancelled'
                """;
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ps.setString(2, message);
            ps.setInt(3, tripId);
            return ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
        return 0;
    }

    /**
     * Returns all notifications enriched with recipient name, email and role,
     * ordered newest-first — used by the admin Messages view.
     */
    public List<Map<String, Object>> findAllNotifications() {
        ensureNotificationsTable();
        String sql = """
                SELECT n.id, n.user_id, u.full_name, u.email, u.role,
                       n.trip_id, n.message, n.is_read, n.created_at
                FROM   notifications n
                JOIN   users u ON u.id = n.user_id
                ORDER  BY n.created_at DESC
                """;
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",        rs.getInt("id"));
                row.put("userId",    rs.getInt("user_id"));
                row.put("fullName",  rs.getString("full_name"));
                row.put("email",     rs.getString("email"));
                row.put("role",      rs.getString("role"));
                row.put("tripId",    rs.getObject("trip_id"));
                row.put("message",   rs.getString("message"));
                row.put("read",      rs.getBoolean("is_read"));
                row.put("createdAt", rs.getString("created_at"));
                rows.add(row);
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return rows;
    }

    /**
     * Sends a manual message from admin to a specific user (operator or driver).
     *
     * @return generated notification id, or -1 on failure
     */
    public int sendNotificationToUser(int userId, String message) {
        ensureNotificationsTable();
        String sql = "INSERT INTO notifications (user_id, trip_id, message) VALUES (?, NULL, ?)";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, userId);
            ps.setString(2, message);
            if (ps.executeUpdate() == 1) {
                ResultSet keys = ps.getGeneratedKeys();
                if (keys.next()) return keys.getInt(1);
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return -1;
    }

    // ─────────────────────────────────────────────
    //  Mapping helper
    // ─────────────────────────────────────────────

    private User mapUser(ResultSet rs) throws SQLException {
        User user = new User();
        user.setId(rs.getInt("id"));
        user.setFullName(rs.getString("full_name"));
        user.setEmail(rs.getString("email"));
        user.setRole(rs.getString("role"));
        int terminalId = rs.getInt("terminal_id");
        user.setTerminalId(rs.wasNull() ? null : terminalId);
        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) user.setCreatedAt(created.toLocalDateTime());
        user.setActive(rs.getBoolean("is_active"));
        return user;
    }
}
