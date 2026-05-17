package al.albus.repository;

import al.albus.config.DatabaseConfig;
import al.albus.model.Route;
import al.albus.model.RouteRequest;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class RouteRepository {

    // ── Routes ────────────────────────────────────────────────

    public int createRoute(int originTerminalId, String origin,
                           String destination, int distanceKm) {
        int destId = findTerminalIdByCity(destination);
        if (destId == -1) return -1;
        if (!routePairAllowed(originTerminalId, destId)) return -1;

        String sql = "INSERT INTO routes (origin_id, destination_id, distance_km) " +
                "VALUES (?, ?, ?)";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql,
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, originTerminalId);
            ps.setInt(2, destId);
            ps.setInt(3, distanceKm);
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) return keys.getInt(1);
        } catch (SQLException e) { e.printStackTrace(); }
        return -1;
    }

    public boolean updateRoute(int routeId, String origin,
                               String destination, int distanceKm) {
        int destId = findTerminalIdByCity(destination);
        if (destId == -1) return false;
        int originId = findRouteOriginId(routeId);
        if (originId <= 0 || !routePairAllowed(originId, destId)) return false;
        String sql = "UPDATE routes SET destination_id=?, distance_km=? WHERE id=?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, destId);
            ps.setInt(2, distanceKm);
            ps.setInt(3, routeId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    /**
     * Routes where this terminal is the origin.
     * Status is derived from the latest route_request for this route.
     */
    public List<Route> findByOriginTerminal(int terminalId) {
        ensureRoutesFromTerminal(terminalId);
        String sql = ROUTE_BASE_SQL + " WHERE r.origin_id = ? " +
                "ORDER BY t_dest.city";
        return queryRoutes(sql, terminalId);
    }

    public List<Route> findAll() {
        String sql = ROUTE_BASE_SQL + " ORDER BY t_orig.city, t_dest.city";
        List<Route> list = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            mapRoutes(ps.executeQuery(), list);
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    /** All approved routes — for trip scheduling dropdowns. */
    public List<Route> findApproved() {
        String sql = ROUTE_BASE_SQL +
                " WHERE rr.status = 'approved' " +
                "ORDER BY t_orig.city, t_dest.city";
        List<Route> list = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            mapRoutes(ps.executeQuery(), list);
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    // ── Route Requests ────────────────────────────────────────

    public int sendRouteRequest(int routeId, int requestingOperatorId,
                                int receivingOperatorId) {
        return sendRouteRequest(routeId, requestingOperatorId, receivingOperatorId, null, null, null);
    }

    public int sendRouteRequest(int routeId, int requestingOperatorId,
                                int receivingOperatorId, Integer sourceTripId) {
        return sendRouteRequest(routeId, requestingOperatorId, receivingOperatorId, sourceTripId, null, null);
    }

    public int sendRouteRequest(int routeId, int requestingOperatorId,
                                int receivingOperatorId, Integer sourceTripId,
                                String requestedDepartureTime, String requestedArrivalTime) {
        return sendRouteRequest(routeId, requestingOperatorId, receivingOperatorId, sourceTripId,
                requestedDepartureTime, requestedArrivalTime, null, null, null);
    }

    public int sendRouteRequest(int routeId, int requestingOperatorId,
                                int receivingOperatorId, Integer sourceTripId,
                                String requestedDepartureTime, String requestedArrivalTime,
                                Integer plannedDriverId, Integer plannedBusId,
                                java.math.BigDecimal plannedPrice) {
        ensureRequestTripColumns();
        String sql = "INSERT INTO route_requests " +
                "(route_id, requesting_operator, receiving_operator, status, source_trip_id, requested_departure_time, requested_arrival_time, planned_driver_id, planned_bus_id, planned_price) " +
                "VALUES (?, ?, ?, 'pending', ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql,
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, routeId);
            ps.setInt(2, requestingOperatorId);
            ps.setInt(3, receivingOperatorId);
            if (sourceTripId == null) ps.setNull(4, Types.INTEGER);
            else ps.setInt(4, sourceTripId);
            ps.setString(5, requestedDepartureTime);
            ps.setString(6, requestedArrivalTime);
            if (plannedDriverId == null) ps.setNull(7, Types.INTEGER);
            else ps.setInt(7, plannedDriverId);
            if (plannedBusId == null) ps.setNull(8, Types.INTEGER);
            else ps.setInt(8, plannedBusId);
            if (plannedPrice == null) ps.setNull(9, Types.DECIMAL);
            else ps.setBigDecimal(9, plannedPrice);
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) return keys.getInt(1);
        } catch (SQLException e) { e.printStackTrace(); }
        return -1;
    }

    /**
     * Incoming — this operator is the receiving_operator.
     * Uses JOIN instead of subquery to avoid MariaDB LIMIT-in-subquery issue.
     */
    public List<RouteRequest> findIncomingRequests(int receivingOperatorId) {
        ensureRequestTripColumns();
        String sql = REQUEST_BASE_SQL +
                " WHERE rr.receiving_operator = ? " +
                "ORDER BY rr.created_at DESC";
        return queryRequests(sql, receivingOperatorId);
    }

    /** Outgoing — this operator is the requesting_operator. */
    public List<RouteRequest> findOutgoingRequests(int requestingOperatorId) {
        ensureRequestTripColumns();
        String sql = REQUEST_BASE_SQL +
                " WHERE rr.requesting_operator = ? " +
                "ORDER BY rr.created_at DESC";
        return queryRequests(sql, requestingOperatorId);
    }

    public boolean approveRequest(int requestId) {
        return updateRequestStatus(requestId, "approved", null);
    }

    public int findSourceTripIdForRequest(int requestId) {
        ensureRequestTripColumns();
        String sql = "SELECT source_trip_id FROM route_requests WHERE id = ? LIMIT 1";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, requestId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("source_trip_id");
        } catch (SQLException e) { e.printStackTrace(); }
        return 0;
    }

    public int findScheduledTripIdForRequest(int requestId) {
        ensureRequestTripColumns();
        String sql = "SELECT scheduled_trip_id FROM route_requests WHERE id = ? LIMIT 1";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, requestId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("scheduled_trip_id");
        } catch (SQLException e) { e.printStackTrace(); }
        return 0;
    }

    public boolean markRequestScheduledTrip(int requestId, int tripId) {
        ensureRequestTripColumns();
        String sql = "UPDATE route_requests SET scheduled_trip_id = ? WHERE id = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ps.setInt(2, requestId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    public boolean rejectRequest(int requestId, String reason) {
        return updateRequestStatus(requestId, "rejected", reason);
    }

    public int findTerminalIdByCity(String city) {
        String sql = """
                SELECT id
                FROM terminals
                WHERE city = ?
                   OR name = ?
                   OR TRIM(CONCAT(city, ' ', COALESCE(name, ''))) = ?
                LIMIT 1
                """;
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, city);
            ps.setString(2, city);
            ps.setString(3, city);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("id");
        } catch (SQLException e) { e.printStackTrace(); }
        return -1;
    }

    public boolean existsById(int routeId) {
        String sql = "SELECT 1 FROM routes WHERE id = ? LIMIT 1";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, routeId);
            return ps.executeQuery().next();
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    public int removeRequestedRoutesWithoutTrips() {
        String selectSql = """
                SELECT DISTINCT r.id
                FROM routes r
                JOIN route_requests rr ON rr.route_id = r.id
                LEFT JOIN trips tr ON tr.route_id = r.id
                WHERE tr.id IS NULL
                """;
        List<Integer> ids = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(selectSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) ids.add(rs.getInt("id"));
        } catch (SQLException e) {
            e.printStackTrace();
            return 0;
        }
        int removed = 0;
        for (int id : ids) {
            if (deleteRouteRequestOnly(id)) removed++;
        }
        return removed;
    }

    private boolean deleteRouteRequestOnly(int routeId) {
        String deleteRequests = "DELETE FROM route_requests WHERE route_id = ?";
        String deleteRoute = """
                DELETE r FROM routes r
                LEFT JOIN trips tr ON tr.route_id = r.id
                WHERE r.id = ? AND tr.id IS NULL
                """;
        try (Connection conn = DatabaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement req = conn.prepareStatement(deleteRequests);
                 PreparedStatement route = conn.prepareStatement(deleteRoute)) {
                req.setInt(1, routeId);
                req.executeUpdate();
                route.setInt(1, routeId);
                boolean deleted = route.executeUpdate() == 1;
                conn.commit();
                return deleted;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    public String findTerminalCityById(int terminalId) {
        String sql = "SELECT city FROM terminals WHERE id = ? LIMIT 1";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, terminalId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("city");
        } catch (SQLException e) { e.printStackTrace(); }
        return "";
    }

    public int findOriginTerminalIdByRouteId(int routeId) {
        String sql = "SELECT origin_id FROM routes WHERE id = ? LIMIT 1";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, routeId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("origin_id");
        } catch (SQLException e) { e.printStackTrace(); }
        return -1;
    }

    public int findOperatorByTerminal(int terminalId) {
        String sql = "SELECT id FROM users WHERE terminal_id = ? " +
                "AND role = 'operator' LIMIT 1";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, terminalId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("id");
        } catch (SQLException e) { e.printStackTrace(); }
        return -1;
    }

    // ── SQL constants ─────────────────────────────────────────

    /**
     * Route base SQL — JOINs terminals twice for city names.
     * LEFT JOINs route_requests to get approval status.
     */
    public int findOrCreateRoute(int originTerminalId, int destinationTerminalId) {
        if (!routePairAllowed(originTerminalId, destinationTerminalId)) return -1;
        String findSql = "SELECT id FROM routes WHERE origin_id = ? AND destination_id = ? LIMIT 1";
        String citySql = """
                SELECT o.city AS origin_city, d.city AS destination_city
                FROM terminals o
                JOIN terminals d ON d.id = ?
                WHERE o.id = ?
                """;
        String insertSql = "INSERT INTO routes (origin_id, destination_id, distance_km) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseConfig.getConnection()) {
            try (PreparedStatement find = conn.prepareStatement(findSql)) {
                find.setInt(1, originTerminalId);
                find.setInt(2, destinationTerminalId);
                ResultSet rs = find.executeQuery();
                if (rs.next()) return rs.getInt("id");
            }

            String originCity;
            String destinationCity;
            try (PreparedStatement city = conn.prepareStatement(citySql)) {
                city.setInt(1, destinationTerminalId);
                city.setInt(2, originTerminalId);
                ResultSet rs = city.executeQuery();
                if (!rs.next()) return -1;
                originCity = rs.getString("origin_city");
                destinationCity = rs.getString("destination_city");
            }

            try (PreparedStatement insert = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                insert.setInt(1, originTerminalId);
                insert.setInt(2, destinationTerminalId);
                insert.setInt(3, estimateDistanceKm(originCity, destinationCity));
                insert.executeUpdate();
                ResultSet keys = insert.getGeneratedKeys();
                if (keys.next()) return keys.getInt(1);
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return -1;
    }

    private void ensureRoutesFromTerminal(int originTerminalId) {
        String originSql = "SELECT city FROM terminals WHERE id = ? LIMIT 1";
        String terminalSql = """
                SELECT id, city
                FROM terminals
                WHERE id <> ?
                  AND city <> ?
                ORDER BY city
                """;
        String existsSql = "SELECT 1 FROM routes WHERE origin_id = ? AND destination_id = ? LIMIT 1";
        String insertSql = "INSERT INTO routes (origin_id, destination_id, distance_km) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement originPs = conn.prepareStatement(originSql)) {
            originPs.setInt(1, originTerminalId);
            ResultSet originRs = originPs.executeQuery();
            if (!originRs.next()) return;
            String originCity = originRs.getString("city");

            try (PreparedStatement terminals = conn.prepareStatement(terminalSql);
                 PreparedStatement exists = conn.prepareStatement(existsSql);
                 PreparedStatement insert = conn.prepareStatement(insertSql)) {
                terminals.setInt(1, originTerminalId);
                terminals.setString(2, originCity);
                ResultSet rs = terminals.executeQuery();
                while (rs.next()) {
                    int destinationId = rs.getInt("id");
                    String destinationCity = rs.getString("city");
                    if (!routePairAllowed(originTerminalId, destinationId)) continue;
                    exists.setInt(1, originTerminalId);
                    exists.setInt(2, destinationId);
                    if (exists.executeQuery().next()) continue;
                    insert.setInt(1, originTerminalId);
                    insert.setInt(2, destinationId);
                    insert.setInt(3, estimateDistanceKm(originCity, destinationCity));
                    insert.executeUpdate();
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private int estimateDistanceKm(String originCity, String destinationCity) {
        String a = normalizeCity(originCity);
        String b = normalizeCity(destinationCity);
        String key = a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
        return switch (key) {
            case "durres|tirane" -> 30;
            case "shkoder|tirane" -> 116;
            case "tirane|vlore" -> 147;
            case "korce|tirane" -> 181;
            case "elbasan|tirane" -> 40;
            case "gjirokaster|tirane" -> 230;
            case "durres|shkoder" -> 108;
            case "durres|vlore" -> 124;
            case "durres|korce" -> 210;
            case "durres|elbasan" -> 77;
            case "durres|gjirokaster" -> 204;
            case "shkoder|vlore" -> 252;
            case "korce|shkoder" -> 292;
            case "elbasan|shkoder" -> 197;
            case "gjirokaster|shkoder" -> 320;
            case "korce|vlore" -> 185;
            case "elbasan|vlore" -> 126;
            case "gjirokaster|vlore" -> 97;
            case "elbasan|korce" -> 140;
            case "gjirokaster|korce" -> 190;
            case "elbasan|gjirokaster" -> 196;
            default -> 100;
        };
    }

    private int findRouteOriginId(int routeId) {
        String sql = "SELECT origin_id FROM routes WHERE id = ? LIMIT 1";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, routeId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("origin_id");
        } catch (SQLException e) { e.printStackTrace(); }
        return -1;
    }

    private boolean routePairAllowed(int originTerminalId, int destinationTerminalId) {
        String sql = """
                SELECT id, city, COALESCE(name, '') AS name
                FROM terminals
                WHERE id IN (?, ?)
                """;
        TerminalInfo origin = null;
        TerminalInfo destination = null;
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, originTerminalId);
            ps.setInt(2, destinationTerminalId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                TerminalInfo terminal = new TerminalInfo(
                        rs.getInt("id"),
                        normalizeCity(rs.getString("city")),
                        normalizeCity(rs.getString("name"))
                );
                if (terminal.id() == originTerminalId) origin = terminal;
                if (terminal.id() == destinationTerminalId) destination = terminal;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
        if (origin == null || destination == null) return false;

        boolean originTirana = isTirana(origin);
        boolean destinationTirana = isTirana(destination);

        if (originTirana && destinationTirana) return false;
        if (!originTirana && !destinationTirana) return true;

        if (originTirana) {
            String required = requiredTiranaTerminal(destination.city());
            return required == null || tiranaTerminalMatches(origin, required);
        }

        if (destinationTirana) {
            String required = requiredTiranaTerminal(origin.city());
            return required == null || tiranaTerminalMatches(destination, required);
        }
        return true;
    }

    private String requiredTiranaTerminal(String city) {
        return switch (city) {
            case "vlore", "shkoder", "fier", "durres" -> "veri";
            case "pogradec", "elbasan" -> "lindor";
            default -> null;
        };
    }

    private boolean isTirana(TerminalInfo terminal) {
        return terminal.city().equals("tirane") || terminal.name().contains("tirane");
    }

    private boolean tiranaTerminalMatches(TerminalInfo terminal, String required) {
        if (!isTirana(terminal)) return false;
        return "lindor".equals(required)
                ? terminal.name().contains("lindor")
                : terminal.name().contains("veri") || terminal.name().contains("jug");
    }

    private record TerminalInfo(int id, String city, String name) {}

    private String normalizeCity(String value) {
        if (value == null) return "";
        return value.toLowerCase()
                .replace("ë", "e")
                .replace("ç", "c")
                .replace("ë", "e")
                .replace("ç", "c")
                .trim();
    }

    private static final String ROUTE_BASE_SQL =
            "SELECT r.id, r.origin_id, r.destination_id, r.distance_km, " +
                    "       COALESCE(NULLIF(t_orig.name, ''), t_orig.city) AS origin, " +
                    "       COALESCE(NULLIF(t_dest.name, ''), t_dest.city) AS destination, " +
                    "       COALESCE(rr.status, 'pending') AS status " +
                    "FROM routes r " +
                    "JOIN  terminals t_orig ON r.origin_id      = t_orig.id " +
                    "JOIN  terminals t_dest ON r.destination_id = t_dest.id " +
                    "LEFT JOIN route_requests rr ON rr.id = (" +
                    "       SELECT rr_latest.id FROM route_requests rr_latest " +
                    "       WHERE rr_latest.route_id = r.id " +
                    "       ORDER BY rr_latest.created_at DESC, rr_latest.id DESC " +
                    "       LIMIT 1" +
                    ")";

    /**
     * Request base SQL — avoids LIMIT inside subquery (MariaDB restriction).
     * Gets from_city by JOINing users → terminals via requesting_operator.
     */
    private static final String REQUEST_BASE_SQL =
            "SELECT rr.id, rr.route_id, rr.requesting_operator, rr.receiving_operator, " +
                    "       rr.status, rr.reason, rr.created_at, " +
                    "       COALESCE(NULLIF(t_from.name, ''), t_from.city) AS origin, " +
                    "       COALESCE(NULLIF(t_dest.name, ''), t_dest.city) AS destination, " +
                    "       r.distance_km, " +
                    "       COALESCE(tr_source.departure_time, rr.requested_departure_time) AS requested_departure_time, " +
                    "       COALESCE(tr_source.arrival_time, rr.requested_arrival_time) AS requested_arrival_time, " +
                    "       rr.planned_driver_id, rr.planned_bus_id, rr.planned_price, " +
                    "       COALESCE(NULLIF(t_from.name, ''), t_from.city) AS from_city " +
                    "FROM route_requests rr " +
                    "JOIN routes    r       ON rr.route_id            = r.id " +
                    "JOIN terminals t_orig  ON r.origin_id            = t_orig.id " +
                    "JOIN terminals t_dest  ON r.destination_id       = t_dest.id " +
                    "JOIN users     u_from  ON rr.requesting_operator = u_from.id " +
                    "JOIN terminals t_from  ON u_from.terminal_id     = t_from.id " +
                    "LEFT JOIN trips tr_source ON tr_source.id = rr.source_trip_id";

    // ── private helpers ───────────────────────────────────────

    private boolean updateRequestStatus(int requestId, String status, String reason) {
        ensureRequestTripColumns();
        String sql = "UPDATE route_requests SET status=?, reason=? WHERE id=?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, reason);
            ps.setInt(3, requestId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    private List<Route> queryRoutes(String sql, int param) {
        List<Route> list = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, param);
            mapRoutes(ps.executeQuery(), list);
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    private void mapRoutes(ResultSet rs, List<Route> list) throws SQLException {
        while (rs.next()) {
            Route r = new Route();
            r.setId(rs.getInt("id"));
            r.setOriginTerminalId(rs.getInt("origin_id"));
            r.setOrigin(rs.getString("origin"));
            r.setDestination(rs.getString("destination"));
            r.setDistanceKm(rs.getInt("distance_km"));
            r.setStatus(rs.getString("status"));
            list.add(r);
        }
    }

    public void ensureRequestTripColumns() {
        ensureColumn("route_requests", "source_trip_id",
                "ALTER TABLE route_requests ADD COLUMN source_trip_id INT NULL");
        ensureColumn("route_requests", "scheduled_trip_id",
                "ALTER TABLE route_requests ADD COLUMN scheduled_trip_id INT NULL");
        ensureColumn("route_requests", "requested_departure_time",
                "ALTER TABLE route_requests ADD COLUMN requested_departure_time DATETIME NULL");
        ensureColumn("route_requests", "requested_arrival_time",
                "ALTER TABLE route_requests ADD COLUMN requested_arrival_time DATETIME NULL");
        ensureColumn("route_requests", "planned_driver_id",
                "ALTER TABLE route_requests ADD COLUMN planned_driver_id INT NULL");
        ensureColumn("route_requests", "planned_bus_id",
                "ALTER TABLE route_requests ADD COLUMN planned_bus_id INT NULL");
        ensureColumn("route_requests", "planned_price",
                "ALTER TABLE route_requests ADD COLUMN planned_price DECIMAL(10,2) NULL");
    }

    private void ensureColumn(String table, String column, String alterSql) {
        String checkSql = """
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND column_name = ?
                """;
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getInt(1) > 0) return;
            try (PreparedStatement alter = conn.prepareStatement(alterSql)) {
                alter.executeUpdate();
            }
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private List<RouteRequest> queryRequests(String sql, int param) {
        List<RouteRequest> list = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, param);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                RouteRequest rr = new RouteRequest();
                rr.setId(rs.getInt("id"));
                rr.setRouteId(rs.getInt("route_id"));
                rr.setFromTerminalId(rs.getInt("requesting_operator"));
                rr.setToTerminalId(rs.getInt("receiving_operator"));
                rr.setStatus(rs.getString("status"));
                rr.setRejectionReason(rs.getString("reason"));
                rr.setOrigin(rs.getString("origin"));
                rr.setDestination(rs.getString("destination"));
                rr.setFromCity(rs.getString("from_city"));
                rr.setRequestedDepartureTime(rs.getString("requested_departure_time"));
                rr.setRequestedArrivalTime(rs.getString("requested_arrival_time"));
                rr.setPlannedDriverId(rs.getInt("planned_driver_id"));
                if (rs.wasNull()) rr.setPlannedDriverId(0);
                rr.setPlannedBusId(rs.getInt("planned_bus_id"));
                if (rs.wasNull()) rr.setPlannedBusId(0);
                rr.setPlannedPrice(rs.getBigDecimal("planned_price"));
                rr.setCreatedAt(rs.getString("created_at"));
                rr.setDistanceKm(rs.getInt("distance_km"));
                list.add(rr);
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    public boolean routeBelongsToTerminal(int routeId, int terminalId) {
        String sql = "SELECT 1 FROM routes WHERE id = ? AND origin_id = ? LIMIT 1";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, routeId);
            ps.setInt(2, terminalId);
            return ps.executeQuery().next();
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }
}
