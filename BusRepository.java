package al.albus.repository;

import al.albus.config.DatabaseConfig;
import al.albus.model.Bus;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BusRepository {

    public BusRepository() {
        ensureSchema();
    }

    public void ensureSchema() {
        String createBuses = """
                CREATE TABLE IF NOT EXISTS buses (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    terminal_id INT NOT NULL,
                    plate_number VARCHAR(32) NOT NULL UNIQUE,
                    model VARCHAR(100) NOT NULL,
                    seat_count INT NOT NULL,
                    status VARCHAR(30) NOT NULL DEFAULT 'available',
                    is_active BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_buses_terminal_id (terminal_id)
                )
                """;
        try (Connection conn = DatabaseConfig.getConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate(createBuses);
            if (!columnExists(conn, "trips", "bus_id")) {
                st.executeUpdate("ALTER TABLE trips ADD COLUMN bus_id INT NULL AFTER driver_id");
            }
            if (!indexExists(conn, "trips", "idx_trips_bus_id")) {
                st.executeUpdate("ALTER TABLE trips ADD INDEX idx_trips_bus_id (bus_id)");
            }
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public List<Bus> findAll() {
        String sql = BASE_SQL + " ORDER BY terminal_name, b.plate_number";
        return queryBuses(sql);
    }

    public List<Bus> findByTerminal(int terminalId) {
        String sql = BASE_SQL + " WHERE b.terminal_id = ? ORDER BY b.plate_number";
        return queryBuses(sql, terminalId);
    }

    public int create(int terminalId, String plateNumber, String model, int seatCount, String status) {
        String sql = """
                INSERT INTO buses (terminal_id, plate_number, model, seat_count, status, is_active)
                VALUES (?, ?, ?, ?, ?, TRUE)
                """;
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, terminalId);
            ps.setString(2, plateNumber);
            ps.setString(3, model);
            ps.setInt(4, seatCount);
            ps.setString(5, normalizeStoredStatus(status));
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) return keys.getInt(1);
        } catch (SQLException e) { e.printStackTrace(); }
        return -1;
    }

    public boolean updateStatusForTerminal(int id, int terminalId, String status) {
        String sql = """
                UPDATE buses
                SET status = ?
                WHERE id = ?
                  AND terminal_id = ?
                  AND is_active = TRUE
                """;
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalizeStoredStatus(status));
            ps.setInt(2, id);
            ps.setInt(3, terminalId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    public boolean update(int id, int terminalId, String plateNumber, String model, int seatCount, String status) {
        String sql = """
                UPDATE buses
                SET terminal_id = ?, plate_number = ?, model = ?, seat_count = ?, status = ?
                WHERE id = ? AND is_active = TRUE
                """;
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, terminalId);
            ps.setString(2, plateNumber);
            ps.setString(3, model);
            ps.setInt(4, seatCount);
            ps.setString(5, normalizeStoredStatus(status));
            ps.setInt(6, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    public boolean deactivate(int id) {
        String sql = "UPDATE buses SET is_active = FALSE, status = 'inactive' WHERE id = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    public boolean isAssignableToTerminal(int busId, int terminalId) {
        String sql = """
                SELECT 1
                FROM buses
                WHERE id = ?
                  AND terminal_id = ?
                  AND is_active = TRUE
                  AND LOWER(status) = 'available'
                LIMIT 1
                """;
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, busId);
            ps.setInt(2, terminalId);
            return ps.executeQuery().next();
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    public int seatCount(int busId) {
        String sql = "SELECT seat_count FROM buses WHERE id = ? AND is_active = TRUE";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, busId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("seat_count");
        } catch (SQLException e) { e.printStackTrace(); }
        return 0;
    }

    public Map<String, Object> findFirstAssignableBus(int terminalId) {
        List<Map<String, Object>> buses = availabilityForTerminal(terminalId);
        return buses.stream()
                .filter(b -> "Available".equals(b.get("status")))
                .findFirst()
                .orElse(null);
    }

    public List<Map<String, Object>> availabilityForTerminal(int terminalId) {
        String sql = """
                SELECT b.id, b.terminal_id, b.plate_number, b.model, b.seat_count,
                       b.status AS stored_status, b.is_active,
                       tr.id AS trip_id,
                       COALESCE(NULLIF(t_orig.name, ''), t_orig.city) AS origin,
                       COALESCE(NULLIF(t_dest.name, ''), t_dest.city) AS destination,
                       tr.departure_time,
                       u.full_name AS driver_name
                FROM buses b
                LEFT JOIN trips tr
                       ON tr.bus_id = b.id
                      AND LOWER(tr.status) IN ('scheduled', 'departed')
                LEFT JOIN terminals t_orig ON tr.origin_terminal_id = t_orig.id
                LEFT JOIN terminals t_dest ON tr.destination_terminal_id = t_dest.id
                LEFT JOIN users u ON tr.driver_id = u.id
                WHERE b.terminal_id = ?
                ORDER BY b.plate_number, tr.departure_time
                """;
        Map<Integer, Map<String, Object>> rows = new LinkedHashMap<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, terminalId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                int busId = rs.getInt("id");
                Map<String, Object> row = rows.computeIfAbsent(busId, id -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    try {
                        item.put("id", id);
                        item.put("terminalId", rs.getInt("terminal_id"));
                        item.put("plateNumber", rs.getString("plate_number"));
                        item.put("model", rs.getString("model"));
                        item.put("seatCount", rs.getInt("seat_count"));
                        item.put("storedStatus", rs.getString("stored_status"));
                        item.put("active", rs.getBoolean("is_active"));
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                    return item;
                });
                if (rs.getObject("trip_id") != null && !row.containsKey("tripId")) {
                    row.put("tripId", rs.getInt("trip_id"));
                    row.put("route", rs.getString("origin") + " to " + rs.getString("destination"));
                    row.put("driverName", rs.getString("driver_name"));
                    row.put("departureTime", rs.getString("departure_time"));
                }
            }
        } catch (SQLException e) { e.printStackTrace(); }

        rows.values().forEach(row -> row.put("status", displayStatus(
                Boolean.TRUE.equals(row.get("active")),
                String.valueOf(row.get("storedStatus")),
                row.containsKey("tripId")
        )));
        return new ArrayList<>(rows.values());
    }

    private static final String BASE_SQL = """
            SELECT b.id, b.terminal_id,
                   COALESCE(NULLIF(t.name, ''), t.city) AS terminal_name,
                   b.plate_number, b.model, b.seat_count, b.status, b.is_active
            FROM buses b
            JOIN terminals t ON t.id = b.terminal_id
            """;

    private List<Bus> queryBuses(String sql, Object... params) {
        List<Bus> rows = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) rows.add(mapBus(rs));
        } catch (SQLException e) { e.printStackTrace(); }
        return rows;
    }

    private Bus mapBus(ResultSet rs) throws SQLException {
        Bus bus = new Bus();
        bus.setId(rs.getInt("id"));
        bus.setTerminalId(rs.getInt("terminal_id"));
        bus.setTerminalName(rs.getString("terminal_name"));
        bus.setPlateNumber(rs.getString("plate_number"));
        bus.setModel(rs.getString("model"));
        bus.setSeatCount(rs.getInt("seat_count"));
        bus.setActive(rs.getBoolean("is_active"));
        bus.setStatus(displayStatus(bus.isActive(), rs.getString("status"), false));
        return bus;
    }

    private String normalizeStoredStatus(String status) {
        status = status == null ? "available" : status.trim().toLowerCase();
        if (status.equals("under maintenance")) return "maintenance";
        if (status.equals("in service") || status.equals("in-service")) return "in_service";
        if (!status.equals("maintenance") && !status.equals("inactive") && !status.equals("in_service")) return "available";
        return status;
    }

    private String displayStatus(boolean active, String storedStatus, boolean hasActiveTrip) {
        storedStatus = storedStatus == null ? "" : storedStatus.toLowerCase();
        if (!active || storedStatus.equals("inactive")) return "Inactive";
        if (storedStatus.equals("maintenance")) return "Under Maintenance";
        if (storedStatus.equals("in_service")) return "In Service";
        return "Available";
    }

    private boolean columnExists(Connection conn, String table, String column) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT 1
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND column_name = ?
                LIMIT 1
                """)) {
            ps.setString(1, table);
            ps.setString(2, column);
            return ps.executeQuery().next();
        }
    }

    private boolean indexExists(Connection conn, String table, String indexName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT 1
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND index_name = ?
                LIMIT 1
                """)) {
            ps.setString(1, table);
            ps.setString(2, indexName);
            return ps.executeQuery().next();
        }
    }
}
