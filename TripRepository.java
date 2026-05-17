package al.albus.repository;

import al.albus.config.DatabaseConfig;
import al.albus.model.Trip;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.time.LocalDate;

public class TripRepository {
    public TripRepository() {
        new BusRepository().ensureSchema();
    }

    // ── Base SQL — JOINs terminals for city names ─────────────
    private static final String BASE_SQL =
            "SELECT t.*, " +
                    "       COALESCE(NULLIF(t_orig.name, ''), t_orig.city) AS origin, " +
                    "       COALESCE(NULLIF(t_dest.name, ''), t_dest.city) AS destination, " +
                    "       u.full_name AS driver_name, " +
                    "       b.plate_number AS bus_plate_number, " +
                    "       b.model AS bus_model, " +
                    "       COALESCE(b.seat_count, t.total_seats) AS derived_total_seats, " +
                    "       GREATEST(COALESCE(b.seat_count, t.total_seats) - COALESCE(seat_counts.reserved_seats, 0), 0) AS computed_available_seats " +
                    "FROM trips t " +
                    "LEFT JOIN buses b ON b.id = t.bus_id " +
                    "JOIN terminals t_orig ON t.origin_terminal_id = t_orig.id " +
                    "JOIN terminals t_dest ON t.destination_terminal_id = t_dest.id " +
                    "JOIN users u ON t.driver_id = u.id " +
                    "LEFT JOIN ( " +
                    "    SELECT b.trip_id, COUNT(pd.id) AS reserved_seats " +
                    "    FROM bookings b " +
                    "    JOIN passengers_detail pd ON pd.booking_id = b.id " +
                    "    WHERE LOWER(COALESCE(b.payment_status, '')) <> 'cancelled' " +
                    "    GROUP BY b.trip_id " +
                    ") seat_counts ON seat_counts.trip_id = t.id ";

    public void normalizeStatusValues() {
        String sql = """
                UPDATE trips
                SET status = LOWER(status)
                WHERE LOWER(status) IN ('scheduled', 'departed', 'arrived', 'cancelled')
                  AND BINARY status <> LOWER(status)
                """;
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public void refreshStatusesByTime() {
        normalizeStatusValues();
        String futureSql = """
                UPDATE trips
                SET status = 'scheduled'
                WHERE departure_time > NOW()
                  AND LOWER(status) IN ('departed', 'arrived')
                """;
        String departedSql = """
                UPDATE trips
                SET status = 'departed'
                WHERE departure_time <= NOW()
                  AND arrival_time > NOW()
                  AND LOWER(status) IN ('scheduled', 'arrived')
                """;
        String arrivedSql = """
                UPDATE trips
                SET status = 'arrived'
                WHERE arrival_time <= NOW()
                  AND LOWER(status) IN ('scheduled', 'departed')
                """;
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement future = conn.prepareStatement(futureSql);
             PreparedStatement departed = conn.prepareStatement(departedSql);
             PreparedStatement arrived = conn.prepareStatement(arrivedSql)) {
            future.executeUpdate();
            departed.executeUpdate();
            arrived.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private String normalizeStatus(String status) {
        return status == null ? null : status.toLowerCase();
    }

    // ── Create ────────────────────────────────────────────────
    public int createTrip(Trip t) {
        String sql = "INSERT INTO trips (route_id, bus_id, driver_id, origin_terminal_id, " +
                "destination_terminal_id, departure_time, arrival_time, " +
                "total_seats, available_seats, price, status) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            // Resolve destination terminal id from route
            int destTerminalId = getDestinationTerminalId(conn, t.getRouteId());

            ps.setInt(1, t.getRouteId());
            ps.setInt(2, t.getBusId());
            ps.setInt(3, t.getDriverId());
            ps.setInt(4, t.getTerminalId());
            ps.setInt(5, destTerminalId);
            ps.setString(6, t.getDepartureTime());
            ps.setString(7, t.getArrivalTime());
            ps.setInt(8, t.getTotalSeats());
            ps.setInt(9, t.getTotalSeats());
            ps.setBigDecimal(10, t.getPrice());
            ps.setString(11, normalizeStatus(t.getStatus()));
            ps.executeUpdate();

            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) return keys.getInt(1);
        } catch (SQLException e) { e.printStackTrace(); }
        return -1;
    }

    // ── Update ────────────────────────────────────────────────
    public boolean updateTrip(Trip t) {
        String sql = "UPDATE trips SET route_id=?, bus_id=?, driver_id=?, " +
                "departure_time=?, arrival_time=?, total_seats=?, " +
                "price=?, status=? WHERE id=?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, t.getRouteId());
            ps.setInt(2, t.getBusId());
            ps.setInt(3, t.getDriverId());
            ps.setString(4, t.getDepartureTime());
            ps.setString(5, t.getArrivalTime());
            ps.setInt(6, t.getTotalSeats());
            ps.setBigDecimal(7, t.getPrice());
            ps.setString(8, normalizeStatus(t.getStatus()));
            ps.setInt(9, t.getId());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    public boolean updateTripDetails(Trip t) {
        String sql = "UPDATE trips SET route_id=?, bus_id=?, driver_id=?, " +
                "departure_time=?, arrival_time=?, total_seats=?, " +
                "price=? WHERE id=?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, t.getRouteId());
            ps.setInt(2, t.getBusId());
            ps.setInt(3, t.getDriverId());
            ps.setString(4, t.getDepartureTime());
            ps.setString(5, t.getArrivalTime());
            ps.setInt(6, t.getTotalSeats());
            ps.setBigDecimal(7, t.getPrice());
            ps.setInt(8, t.getId());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    // ── Status transition ─────────────────────────────────────
    public boolean updateStatus(int tripId, String newStatus) {
        normalizeStatusValues();
        String sql = "UPDATE trips SET status=? WHERE id=?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalizeStatus(newStatus));
            ps.setInt(2, tripId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    // ── Find by origin terminal ───────────────────────────────
    public List<Trip> findByTerminal(int terminalId) {
        String sql = BASE_SQL +
                "WHERE t.origin_terminal_id = ? " +
                "ORDER BY t.departure_time";
        return query(sql, terminalId);
    }

    public boolean belongsToOriginTerminal(int tripId, int terminalId) {
        String sql = "SELECT 1 FROM trips WHERE id = ? AND origin_terminal_id = ? LIMIT 1";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ps.setInt(2, terminalId);
            ResultSet rs = ps.executeQuery();
            return rs.next();
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    public String findStatusById(int tripId) {
        refreshStatusesByTime();
        String sql = "SELECT LOWER(status) AS status FROM trips WHERE id = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("status");
        } catch (SQLException e) { e.printStackTrace(); }
        return null;
    }

    public int findRouteIdById(int tripId) {
        String sql = "SELECT route_id FROM trips WHERE id = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("route_id");
        } catch (SQLException e) { e.printStackTrace(); }
        return -1;
    }

    public int countPassengers(int tripId) {
        String sql = """
                SELECT COUNT(pd.id)
                FROM bookings b
                JOIN passengers_detail pd ON pd.booking_id = b.id
                WHERE b.trip_id = ?
                  AND LOWER(COALESCE(b.payment_status, '')) <> 'cancelled'
                """;
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) { e.printStackTrace(); }
        return 0;
    }

    public boolean hasScheduleTimeChange(int tripId, String departureTime, String arrivalTime) {
        String sql = """
                SELECT 1
                FROM trips
                WHERE id = ?
                  AND (departure_time <> ? OR arrival_time <> ?)
                """;
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ps.setString(2, departureTime);
            ps.setString(3, arrivalTime);
            return ps.executeQuery().next();
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    public int[] findAssignmentById(int tripId) {
        String sql = "SELECT driver_id, origin_terminal_id FROM trips WHERE id = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new int[] { rs.getInt("driver_id"), rs.getInt("origin_terminal_id") };
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return null;
    }

    public List<Trip> findAll() {
        refreshStatusesByTime();
        String sql = BASE_SQL + "ORDER BY t.departure_time";
        List<Trip> list = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            mapResults(ps.executeQuery(), list);
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    public Trip findById(int tripId) {
        refreshStatusesByTime();
        String sql = BASE_SQL + "WHERE t.id = ?";
        List<Trip> matches = query(sql, tripId);
        return matches.isEmpty() ? null : matches.get(0);
    }

    public int findDestinationTerminalIdById(int tripId) {
        String sql = "SELECT destination_terminal_id FROM trips WHERE id = ? LIMIT 1";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("destination_terminal_id");
        } catch (SQLException e) { e.printStackTrace(); }
        return -1;
    }

    // ── Find by driver ────────────────────────────────────────
    public List<Trip> findByDriver(int driverId) {
        String sql = BASE_SQL +
                "WHERE t.driver_id = ? " +
                "ORDER BY t.departure_time";
        return query(sql, driverId);
    }

    // ── Find arriving at terminal (destination city) ──────────
    public List<Trip> findArrivingAtTerminal(int terminalId) {
        String sql = BASE_SQL +
                "WHERE t.destination_terminal_id = ? " +
                "ORDER BY t.departure_time";
        return query(sql, terminalId);
    }

    // ── Seat map ──────────────────────────────────────────────
    public List<Integer> getTakenSeats(int tripId) {
        String sql = "SELECT pd.seat_number FROM passengers_detail pd " +
                "JOIN bookings b ON pd.booking_id = b.id " +
                "WHERE b.trip_id = ? " +
                "AND LOWER(COALESCE(b.payment_status, '')) <> 'cancelled'";
        List<Integer> seats = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) seats.add(rs.getInt("seat_number"));
        } catch (SQLException e) { e.printStackTrace(); }
        return seats;
    }

    // ── Pending cash payments ─────────────────────────────────
    public List<String[]> getPendingCashPassengers(int tripId) {
        String sql = "SELECT b.id, pd.first_name, pd.last_name, pd.seat_number, " +
                "pd.final_price, b.payment_status, b.payment_method " +
                "FROM bookings b " +
                "JOIN passengers_detail pd ON pd.booking_id = b.id " +
                "WHERE b.trip_id = ? AND b.payment_method = 'terminal' " +
                "ORDER BY pd.seat_number";
        List<String[]> rows = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                rows.add(new String[]{
                        rs.getString("id"),
                        rs.getString("first_name") + " " + rs.getString("last_name"),
                        rs.getString("seat_number"),
                        rs.getString("final_price"),
                        rs.getString("payment_status")
                });
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return rows;
    }

    // ── Full passenger manifest ───────────────────────────────
    public List<String[]> getPassengerManifest(int tripId) {
        String sql = "SELECT pd.first_name, pd.last_name, pd.seat_number, pd.age, " +
                "pd.discount_pct, pd.final_price, pd.special_needs, " +
                "b.payment_method, b.payment_status " +
                "FROM bookings b " +
                "JOIN passengers_detail pd ON pd.booking_id = b.id " +
                "WHERE b.trip_id = ? " +
                "ORDER BY pd.seat_number";
        List<String[]> rows = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                rows.add(new String[]{
                        rs.getString("first_name") + " " + rs.getString("last_name"),
                        rs.getString("seat_number"),
                        rs.getString("age"),
                        rs.getString("discount_pct") + "%",
                        rs.getString("final_price") + " L",
                        rs.getBoolean("special_needs") ? "Yes" : "No",
                        rs.getString("payment_method"),
                        rs.getString("payment_status")
                });
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return rows;
    }

    // ── private helpers ───────────────────────────────────────
    private List<Trip> query(String sql, int param) {
        refreshStatusesByTime();
        List<Trip> list = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, param);
            mapResults(ps.executeQuery(), list);
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    private List<Trip> queryByString(String sql, String param) {
        refreshStatusesByTime();
        List<Trip> list = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            mapResults(ps.executeQuery(), list);
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    private void mapResults(ResultSet rs, List<Trip> list) throws SQLException {
        while (rs.next()) {
            Trip t = new Trip();
            t.setId(rs.getInt("id"));
            t.setRouteId(rs.getInt("route_id"));
            try { t.setBusId(rs.getInt("bus_id")); }
            catch (SQLException ignored) {}
            t.setDriverId(rs.getInt("driver_id"));
            t.setTerminalId(rs.getInt("origin_terminal_id"));
            t.setTotalSeats(rs.getInt("derived_total_seats"));
            t.setAvailableSeats(rs.getInt("computed_available_seats"));
            t.setPrice(rs.getBigDecimal("price"));
            String status = rs.getString("status");
            t.setStatus(status == null ? null : status.toLowerCase());
            t.setDepartureTime(rs.getString("departure_time"));
            t.setArrivalTime(rs.getString("arrival_time"));

            // departure_time stored as datetime — extract date part
            Timestamp dep = rs.getTimestamp("departure_time");
            if (dep != null) t.setTripDate(dep.toLocalDateTime().toLocalDate());

            try { t.setOrigin(rs.getString("origin")); }
            catch (SQLException ignored) {}
            try { t.setDestination(rs.getString("destination")); }
            catch (SQLException ignored) {}
            try { t.setDriverName(rs.getString("driver_name")); }
            catch (SQLException ignored) {}
            try { t.setBusPlateNumber(rs.getString("bus_plate_number")); }
            catch (SQLException ignored) {}
            try { t.setBusModel(rs.getString("bus_model")); }
            catch (SQLException ignored) {}

            list.add(t);
        }
    }

    private int getDestinationTerminalId(Connection conn, int routeId) throws SQLException {
        String sql = "SELECT destination_id FROM routes WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, routeId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("destination_id");
        }
        return -1;
    }
    // ── ADD THESE METHODS TO TripRepository.java ─────────────────

    /**
     * Search available trips.
     * Origin required. Destination and date are optional.
     */
    public List<Trip> searchAvailable(String origin, String destination, LocalDate date) {
        refreshStatusesByTime();
        StringBuilder sql = new StringBuilder(BASE_SQL);
        sql.append("WHERE t.status = 'scheduled' ");
        sql.append("AND GREATEST(COALESCE(b.seat_count, t.total_seats) - COALESCE(seat_counts.reserved_seats, 0), 0) > 0 ");
        sql.append("AND t.departure_time > NOW() ");
        sql.append("""
                AND (
                    t_orig.city = ?
                    OR t_orig.name = ?
                    OR TRIM(CONCAT(t_orig.city, ' ', COALESCE(t_orig.name, ''))) = ?
                )
                """);

        if (destination != null && !destination.isEmpty() && !destination.equals("Any")) {
            sql.append("""
                    AND (
                        t_dest.city = ?
                        OR t_dest.name = ?
                        OR TRIM(CONCAT(t_dest.city, ' ', COALESCE(t_dest.name, ''))) = ?
                    )
                    """);
        }
        if (date != null) {
            sql.append("AND DATE(t.departure_time) = ? ");
        }
        sql.append("ORDER BY t.departure_time");

        List<Trip> list = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {

            int idx = 1;
            ps.setString(idx++, origin);
            ps.setString(idx++, origin);
            ps.setString(idx++, origin);
            if (destination != null && !destination.isEmpty() && !destination.equals("Any")) {
                ps.setString(idx++, destination);
                ps.setString(idx++, destination);
                ps.setString(idx++, destination);
            }
            if (date != null) {
                ps.setDate(idx++, Date.valueOf(date));
            }
            mapResults(ps.executeQuery(), list);
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    /** Get total seats for a trip. */
    public int getTotalSeatsForTrip(int tripId) {
        String sql = """
                SELECT COALESCE(b.seat_count, t.total_seats) AS total_seats
                FROM trips t
                LEFT JOIN buses b ON b.id = t.bus_id
                WHERE t.id = ?
                """;
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("total_seats");
        } catch (SQLException e) { e.printStackTrace(); }
        return 0;
    }
}
