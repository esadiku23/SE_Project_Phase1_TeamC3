package al.albus.service;

import al.albus.model.*;
import al.albus.repository.*;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OperatorService — orchestrates all operator-specific business logic.
 *
 * Key rules enforced here:
 *  - Origin of any route is ALWAYS the operator's own terminal city.
 *  - An operator can only schedule trips on approved routes from their terminal.
 *  - Trip status must follow the legal transition: scheduled → departed → arrived.
 *  - Cancelled trips cannot be re-activated.
 */
public class OperatorService {

    private static final String LOW_PASSENGER_CHANGE_MESSAGE =
            "Your bus schedule has changed. You may choose another departure at no extra cost, or cancel your booking.";

    private final TripRepository   tripRepo   = new TripRepository();
    private final RouteRepository  routeRepo  = new RouteRepository();
    private final DriverRepository driverRepo = new DriverRepository();
    private final BusRepository    busRepo    = new BusRepository();
    private final AdminRepository  adminRepo  = new AdminRepository();
    private final PasswordService  pwdSvc     = new PasswordService();

    private Connection conn() throws SQLException {
        return al.albus.config.DatabaseConfig.getConnection();
    }

    private void ensureContactSchema() {
        String createMessages = """
                CREATE TABLE IF NOT EXISTS contact_messages (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    passenger_id INT NOT NULL,
                    operator_id INT NOT NULL,
                    subject VARCHAR(150) NOT NULL,
                    message VARCHAR(1000) NOT NULL,
                    reply VARCHAR(1000) NULL,
                    status VARCHAR(20) NOT NULL DEFAULT 'open',
                    operator_deleted BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    replied_at TIMESTAMP NULL,
                    INDEX idx_contact_passenger (passenger_id),
                    INDEX idx_contact_operator (operator_id)
                )
                """;
        try (Connection conn = al.albus.config.DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(createMessages)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        ensureColumn("contact_messages", "operator_deleted",
                "ALTER TABLE contact_messages ADD COLUMN operator_deleted BOOLEAN NOT NULL DEFAULT FALSE");
    }

    private void ensureTripTemplateSchema() {
        String sql = """
                CREATE TABLE IF NOT EXISTS trip_templates (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    terminal_id INT NOT NULL,
                    operator_id INT NOT NULL,
                    route_id INT NOT NULL,
                    bus_id INT NOT NULL,
                    driver_id INT NOT NULL,
                    departure_time TIME NOT NULL,
                    arrival_time TIME NOT NULL,
                    days_of_week VARCHAR(30) NOT NULL,
                    price DECIMAL(10,2) NOT NULL,
                    is_active BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_trip_templates_terminal (terminal_id),
                    INDEX idx_trip_templates_operator (operator_id)
                )
                """;
        try (Connection conn = al.albus.config.DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void ensureColumn(String table, String column, String alterSql) {
        String checkSql = """
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND column_name = ?
                """;
        try (Connection conn = al.albus.config.DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getInt(1) > 0) return;
            try (PreparedStatement alter = conn.prepareStatement(alterSql)) {
                alter.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ── Trips ─────────────────────────────────────────────────

    public List<Trip> getTripsForTerminal(int terminalId) {
        resetFutureDepartures(terminalId);
        return tripRepo.findByTerminal(terminalId);
    }

    public List<Trip> getArrivingTrips(int terminalId) {
        resetFutureArrivals(terminalId);
        return tripRepo.findArrivingAtTerminal(terminalId);
    }

    public Map<String, Object> getTerminalInfo(int terminalId) {
        String sql = """
                SELECT id, city, COALESCE(NULLIF(name, ''), city) AS terminal_name
                FROM terminals
                WHERE id = ?
                LIMIT 1
                """;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, terminalId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getInt("id"));
                row.put("city", rs.getString("city"));
                row.put("terminalName", rs.getString("terminal_name"));
                return row;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return Map.of("id", terminalId, "terminalName", "Terminal");
    }

    private void resetFutureDepartures(int terminalId) {
        String notDepartedSql = """
                UPDATE trips
                SET status = 'scheduled'
                WHERE origin_terminal_id = ?
                  AND departure_time > NOW()
                  AND LOWER(status) IN ('departed', 'arrived')
                """;
        String notArrivedSql = """
                UPDATE trips
                SET status = 'departed'
                WHERE origin_terminal_id = ?
                  AND departure_time <= NOW()
                  AND arrival_time > NOW()
                  AND LOWER(status) = 'arrived'
                """;
        try (Connection c = conn();
             PreparedStatement notDeparted = c.prepareStatement(notDepartedSql);
             PreparedStatement notArrived = c.prepareStatement(notArrivedSql)) {
            notDeparted.setInt(1, terminalId);
            notDeparted.executeUpdate();
            notArrived.setInt(1, terminalId);
            notArrived.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void resetFutureArrivals(int terminalId) {
        String notDepartedSql = """
                UPDATE trips t
                SET t.status = 'scheduled'
                WHERE t.destination_terminal_id = ?
                  AND t.departure_time > NOW()
                  AND LOWER(t.status) IN ('departed', 'arrived')
                """;
        String notArrivedSql = """
                UPDATE trips t
                SET t.status = 'departed'
                WHERE t.destination_terminal_id = ?
                  AND t.departure_time <= NOW()
                  AND t.arrival_time > NOW()
                  AND LOWER(t.status) = 'arrived'
                """;
        try (Connection c = conn();
             PreparedStatement notDeparted = c.prepareStatement(notDepartedSql);
             PreparedStatement notArrived = c.prepareStatement(notArrivedSql)) {
            notDeparted.setInt(1, terminalId);
            notDeparted.executeUpdate();
            notArrived.setInt(1, terminalId);
            notArrived.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * Schedule a new trip.
     * The operator's terminalId is injected here so the UI cannot spoof it.
     */
    public int scheduleTrip(int routeId, int driverId, int terminalId,
                            LocalDate date, String departure, String arrival,
                            int busId, BigDecimal price) {
        // Guard: route must be approved
        List<Route> approved = routeRepo.findApproved();
        boolean routeOk = approved.stream().anyMatch(r -> r.getId() == routeId);
        if (!routeOk) return -2;   // -2 = route not approved
        if (!adminRepo.hasTerminalCapacity(terminalId, date.toString())) return -3;
        if (!routeRepo.routeBelongsToTerminal(routeId, terminalId)) return -4;
        if (!busRepo.isAssignableToTerminal(busId, terminalId)) return -5;
        int seats = busRepo.seatCount(busId);

        Trip t = new Trip();
        t.setRouteId(routeId);
        t.setBusId(busId);
        t.setDriverId(driverId);
        t.setTerminalId(terminalId);
        t.setTripDate(date);
        t.setDepartureTime(departure);
        t.setArrivalTime(arrival);
        t.setTotalSeats(seats);
        t.setAvailableSeats(seats);
        t.setPrice(price);
        t.setStatus("scheduled");
        int tripId = tripRepo.createTrip(t);
        if (tripId != -1) {
            notifyTripDriver(tripId, "Operator scheduled a trip assigned to you.");
        }
        return tripId;
    }

    public List<Map<String, Object>> getTripTemplates(int terminalId, int operatorId) {
        ensureTripTemplateSchema();
        String sql = """
                SELECT tt.id, tt.route_id, tt.bus_id, tt.driver_id,
                       TIME_FORMAT(tt.departure_time, '%H:%i') AS departure_time,
                       TIME_FORMAT(tt.arrival_time, '%H:%i') AS arrival_time,
                       tt.days_of_week, tt.price, tt.is_active,
                       COALESCE(NULLIF(t_orig.name, ''), t_orig.city) AS origin,
                       COALESCE(NULLIF(t_dest.name, ''), t_dest.city) AS destination,
                       b.plate_number, b.model, b.seat_count,
                       u.full_name AS driver_name
                FROM trip_templates tt
                JOIN routes r ON r.id = tt.route_id
                JOIN terminals t_orig ON t_orig.id = r.origin_id
                JOIN terminals t_dest ON t_dest.id = r.destination_id
                JOIN buses b ON b.id = tt.bus_id
                JOIN users u ON u.id = tt.driver_id
                WHERE tt.terminal_id = ?
                  AND tt.is_active = TRUE
                ORDER BY tt.departure_time, tt.id
                """;
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = al.albus.config.DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, terminalId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getInt("id"));
                row.put("routeId", rs.getInt("route_id"));
                row.put("busId", rs.getInt("bus_id"));
                row.put("driverId", rs.getInt("driver_id"));
                row.put("departureTime", rs.getString("departure_time"));
                row.put("arrivalTime", rs.getString("arrival_time"));
                row.put("daysOfWeek", rs.getString("days_of_week"));
                row.put("price", rs.getBigDecimal("price"));
                row.put("origin", rs.getString("origin"));
                row.put("destination", rs.getString("destination"));
                row.put("busPlateNumber", rs.getString("plate_number"));
                row.put("busModel", rs.getString("model"));
                row.put("seatCount", rs.getInt("seat_count"));
                row.put("driverName", rs.getString("driver_name"));
                rows.add(row);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return rows;
    }

    public int createTripTemplate(int terminalId, int operatorId, int routeId, int driverId, int busId,
                                  String departureTime, String arrivalTime, String daysOfWeek, BigDecimal price) {
        ensureTripTemplateSchema();
        if (!routeRepo.routeBelongsToTerminal(routeId, terminalId)) return -1;
        if (!busRepo.isAssignableToTerminal(busId, terminalId)) return -1;
        if (!isDriverAtTerminal(driverId, terminalId)) return -1;
        String days = normalizeDaysOfWeek(daysOfWeek);
        if (days.isEmpty()) return -1;
        LocalTime departure = LocalTime.parse(normalizeClock(departureTime));
        LocalTime arrival = LocalTime.parse(normalizeClock(arrivalTime));
        if (!arrival.isAfter(departure) || price == null || price.signum() <= 0) return -1;

        String sql = """
                INSERT INTO trip_templates
                    (terminal_id, operator_id, route_id, bus_id, driver_id, departure_time, arrival_time, days_of_week, price)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = al.albus.config.DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, terminalId);
            ps.setInt(2, operatorId);
            ps.setInt(3, routeId);
            ps.setInt(4, busId);
            ps.setInt(5, driverId);
            ps.setString(6, departure.toString());
            ps.setString(7, arrival.toString());
            ps.setString(8, days);
            ps.setBigDecimal(9, price);
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) return keys.getInt(1);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return -1;
    }

    public boolean deleteTripTemplate(int templateId, int terminalId, int operatorId) {
        ensureTripTemplateSchema();
        String sql = """
                UPDATE trip_templates
                SET is_active = FALSE
                WHERE id = ?
                  AND terminal_id = ?
                """;
        try (Connection conn = al.albus.config.DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, templateId);
            ps.setInt(2, terminalId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public Map<String, Object> generateTripTemplateWeek(int templateId, int terminalId, int operatorId, String weekStart) {
        ensureTripTemplateSchema();
        Map<String, Object> template = findTripTemplate(templateId, terminalId, operatorId);
        if (template == null) return Map.of("success", false, "message", "Weekly trip not found.");

        LocalDate start = parseWeekStart(weekStart);
        Set<Integer> days = parseDays(String.valueOf(template.get("daysOfWeek")));
        int routeId = Integer.parseInt(template.get("routeId").toString());
        int busId = Integer.parseInt(template.get("busId").toString());
        int driverId = Integer.parseInt(template.get("driverId").toString());
        BigDecimal price = (BigDecimal) template.get("price");
        String departureClock = String.valueOf(template.get("departureTime"));
        String arrivalClock = String.valueOf(template.get("arrivalTime"));

        int createdTrips = 0;
        int skipped = 0;
        for (int offset = 0; offset < 7; offset++) {
            LocalDate date = start.plusDays(offset);
            if (!days.contains(date.getDayOfWeek().getValue())) continue;
            String departure = date + " " + departureClock;
            String arrival = date + " " + arrivalClock;
            if (tripExists(routeId, departure)) {
                skipped++;
                continue;
            }
            int tripId = scheduleTemplateTrip(routeId, driverId, terminalId, date, departure, arrival, busId, price);
            if (tripId > 0) createdTrips++;
            else skipped++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("createdTrips", createdTrips);
        result.put("skipped", skipped);
        result.put("message", createdTrips + " trips created, " + skipped + " skipped.");
        return result;
    }

    private int scheduleTemplateTrip(int routeId, int driverId, int terminalId,
                                     LocalDate date, String departure, String arrival,
                                     int busId, BigDecimal price) {
        if (!adminRepo.hasTerminalCapacity(terminalId, date.toString())) return -3;
        if (!routeRepo.routeBelongsToTerminal(routeId, terminalId)) return -4;
        if (!busRepo.isAssignableToTerminal(busId, terminalId)) return -5;

        int seats = busRepo.seatCount(busId);
        Trip t = new Trip();
        t.setRouteId(routeId);
        t.setBusId(busId);
        t.setDriverId(driverId);
        t.setTerminalId(terminalId);
        t.setTripDate(date);
        t.setDepartureTime(departure);
        t.setArrivalTime(arrival);
        t.setTotalSeats(seats);
        t.setAvailableSeats(seats);
        t.setPrice(price);
        t.setStatus("scheduled");

        int tripId = tripRepo.createTrip(t);
        if (tripId != -1) {
            notifyTripDriver(tripId, "Operator generated a weekly trip assigned to you.");
        }
        return tripId;
    }

    public boolean editTrip(Trip updated) {
        String currentStatus = tripRepo.findStatusById(updated.getId());
        if (!isTripEditableStatus(currentStatus)) {
            return false;
        }
        if (!adminRepo.hasTerminalCapacity(updated.getTerminalId(), updated.getTripDate().toString(), updated.getId())) {
            return false;
        }
        if (!busRepo.isAssignableToTerminal(updated.getBusId(), updated.getTerminalId())) {
            return false;
        }
        if (!isDriverAvailable(updated.getDriverId(), updated.getDepartureTime(), updated.getArrivalTime(), updated.getId())) {
            return false;
        }
        if (!isBusAvailable(updated.getBusId(), updated.getDepartureTime(), updated.getArrivalTime(), updated.getId())) {
            return false;
        }
        updated.setTotalSeats(busRepo.seatCount(updated.getBusId()));
        if (tripRepo.countPassengers(updated.getId()) > updated.getTotalSeats()) {
            return false;
        }
        updated.setAvailableSeats(updated.getTotalSeats());
        boolean scheduleChanged = tripRepo.hasScheduleTimeChange(
                updated.getId(), updated.getDepartureTime(), updated.getArrivalTime());
        boolean ok = tripRepo.updateTripDetails(updated);
        if (ok) {
            syncTripAvailableSeats(updated.getId(), updated.getTotalSeats());
            if ("needs_reassignment".equals(currentStatus)) {
                tripRepo.updateStatus(updated.getId(), "scheduled");
            }
            notifyTripDriver(updated.getId(), "Operator updated trip details for one of your trips.");
            notifyLowPassengerScheduleChange(updated.getId(), currentStatus, scheduleChanged);
        }
        return ok;
    }

    public boolean tripCanBeEdited(int tripId) {
        return isTripEditableStatus(tripRepo.findStatusById(tripId));
    }

    public int getTripRouteId(int tripId) {
        return tripRepo.findRouteIdById(tripId);
    }

    private boolean isTripEditableStatus(String status) {
        status = status == null ? "" : status.toLowerCase();
        return !status.equals("arrived") && !status.equals("cancelled");
    }

    private String normalizeBusStatus(String status) {
        status = status == null ? "available" : status.trim().toLowerCase();
        if (status.equals("under maintenance")) return "maintenance";
        if (status.equals("in service") || status.equals("in-service")) return "in_service";
        if (!status.equals("maintenance") && !status.equals("inactive") && !status.equals("in_service")) return "available";
        return status;
    }

    private Map<String, Object> reassignTripsFromUnavailableBus(int busId, int terminalId) {
        int reassigned = 0;
        int flagged = 0;
        String affectedSql = """
                SELECT tr.id, tr.departure_time, tr.arrival_time,
                       COALESCE(seats.booked_seats, 0) AS booked_seats
                FROM trips tr
                LEFT JOIN (
                    SELECT b.trip_id, COUNT(pd.id) AS booked_seats
                    FROM bookings b
                    JOIN passengers_detail pd ON pd.booking_id = b.id
                    WHERE LOWER(COALESCE(b.payment_status, '')) <> 'cancelled'
                    GROUP BY b.trip_id
                ) seats ON seats.trip_id = tr.id
                WHERE tr.bus_id = ?
                  AND tr.origin_terminal_id = ?
                  AND tr.departure_time > NOW()
                  AND LOWER(tr.status) = 'scheduled'
                ORDER BY tr.departure_time
                """;

        try (Connection c = conn();
             PreparedStatement affected = c.prepareStatement(affectedSql)) {
            affected.setInt(1, busId);
            affected.setInt(2, terminalId);
            ResultSet rs = affected.executeQuery();
            while (rs.next()) {
                int tripId = rs.getInt("id");
                String departure = rs.getString("departure_time");
                String arrival = rs.getString("arrival_time");
                int requiredSeats = Math.max(1, rs.getInt("booked_seats"));
                Map<String, Object> replacement = findAvailableReplacementBus(
                        terminalId, departure, arrival, busId, requiredSeats);
                if (replacement == null) {
                    if (markTripNeedsReassignment(tripId)) {
                        flagged++;
                        notifyTripDriver(tripId, "Trip needs reassignment because its bus was moved to maintenance.");
                        adminRepo.notifyPassengersForTrip(tripId, LOW_PASSENGER_CHANGE_MESSAGE);
                    }
                } else {
                    int replacementBusId = intValue(replacement.get("id"));
                    int replacementSeats = intValue(replacement.get("seatCount"));
                    if (assignTripBus(tripId, replacementBusId, replacementSeats)) {
                        reassigned++;
                        notifyTripDriver(tripId, "Trip bus changed because the previous bus was moved to maintenance.");
                        adminRepo.notifyPassengersForTrip(tripId, LOW_PASSENGER_CHANGE_MESSAGE);
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reassignedTrips", reassigned);
        result.put("flaggedTrips", flagged);
        if (reassigned > 0 || flagged > 0) {
            result.put("message", "Bus status updated. " + reassigned + " trip(s) reassigned, " + flagged + " need operator reassignment.");
        }
        return result;
    }

    private Map<String, Object> findAvailableReplacementBus(int terminalId, String departureTime, String arrivalTime,
                                                            int excludedBusId, int requiredSeats) {
        String sql = """
                SELECT b.id, b.plate_number, b.model, b.seat_count
                FROM buses b
                WHERE b.terminal_id = ?
                  AND b.id <> ?
                  AND b.is_active = TRUE
                  AND LOWER(COALESCE(b.status, 'available')) = 'available'
                  AND b.seat_count >= ?
                  AND NOT EXISTS (
                      SELECT 1
                      FROM trips tr
                      WHERE tr.bus_id = b.id
                        AND LOWER(tr.status) IN ('scheduled', 'departed')
                        AND tr.departure_time < ?
                        AND tr.arrival_time > ?
                  )
                ORDER BY b.seat_count, b.plate_number
                LIMIT 1
                """;
        try (Connection conn = al.albus.config.DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, terminalId);
            ps.setInt(2, excludedBusId);
            ps.setInt(3, requiredSeats);
            ps.setString(4, arrivalTime);
            ps.setString(5, departureTime);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getInt("id"));
                row.put("plateNumber", rs.getString("plate_number"));
                row.put("model", rs.getString("model"));
                row.put("seatCount", rs.getInt("seat_count"));
                return row;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    private boolean assignTripBus(int tripId, int busId, int totalSeats) {
        String sql = """
                UPDATE trips
                SET bus_id = ?,
                    total_seats = ?,
                    available_seats = GREATEST(? - (
                        SELECT COUNT(*)
                        FROM bookings b
                        JOIN passengers_detail pd ON pd.booking_id = b.id
                        WHERE b.trip_id = trips.id
                          AND LOWER(COALESCE(b.payment_status, '')) <> 'cancelled'
                    ), 0)
                WHERE id = ?
                """;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, busId);
            ps.setInt(2, totalSeats);
            ps.setInt(3, totalSeats);
            ps.setInt(4, tripId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    private void syncTripAvailableSeats(int tripId, int totalSeats) {
        String sql = """
                UPDATE trips
                SET available_seats = GREATEST(? - (
                    SELECT COUNT(*)
                    FROM bookings b
                    JOIN passengers_detail pd ON pd.booking_id = b.id
                    WHERE b.trip_id = trips.id
                      AND LOWER(COALESCE(b.payment_status, '')) <> 'cancelled'
                ), 0)
                WHERE id = ?
                """;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, totalSeats);
            ps.setInt(2, tripId);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private boolean markTripNeedsReassignment(int tripId) {
        String sql = """
                UPDATE trips
                SET status = 'needs_reassignment'
                WHERE id = ?
                  AND LOWER(status) = 'scheduled'
                """;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Legal transitions:
     *   scheduled  → departed | cancelled
     *   departed   → arrived  | cancelled
     *   arrived    → (terminal, no further transitions)
     *   cancelled  → (terminal, no further transitions)
     */
    public boolean transitionTripStatus(int tripId, String currentStatus, String targetStatus) {
        currentStatus = currentStatus == null ? null : currentStatus.toLowerCase();
        targetStatus = targetStatus == null ? null : targetStatus.toLowerCase();
        if (currentStatus == null || targetStatus == null) return false;
        boolean legal = switch (currentStatus) {
            case "scheduled" -> targetStatus.equals("departed") || targetStatus.equals("cancelled");
            case "departed"  -> targetStatus.equals("arrived")  || targetStatus.equals("cancelled");
            default          -> false;   // arrived/cancelled are terminal
        };
        if (!legal) return false;
        boolean ok = transitionStatusWithTimeGate(tripId, currentStatus, targetStatus);
        if (ok && "scheduled".equals(currentStatus) && "cancelled".equals(targetStatus)) {
            notifyLowPassengerScheduleChange(tripId, currentStatus, true);
        }
        return ok;
    }

    private boolean transitionStatusWithTimeGate(int tripId, String currentStatus, String targetStatus) {
        String timeGate = "";
        if ("departed".equals(targetStatus)) {
            timeGate = " AND departure_time <= NOW()";
        } else if ("arrived".equals(targetStatus)) {
            timeGate = " AND arrival_time <= NOW()";
        }

        String sql = """
                UPDATE trips
                SET status = ?
                WHERE id = ?
                  AND LOWER(status) = ?
                """ + timeGate;

        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, targetStatus);
            ps.setInt(2, tripId);
            ps.setString(3, currentStatus);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    private void notifyLowPassengerScheduleChange(int tripId, String currentStatus, boolean scheduleChanged) {
        int passengers = tripRepo.countPassengers(tripId);

        if (scheduleChanged && "scheduled".equals(currentStatus) && passengers <= 2) {
            adminRepo.notifyPassengersForTrip(
                    tripId,
                    LOW_PASSENGER_CHANGE_MESSAGE
            );
        }
    }
    // ── Seat map ──────────────────────────────────────────────

    public List<Integer> getTakenSeats(int tripId) {
        return tripRepo.getTakenSeats(tripId);
    }

    // ── Routes ────────────────────────────────────────────────

    public List<Route> getRoutesForTerminal(int terminalId) {
        return routeRepo.findByOriginTerminal(terminalId);
    }

    public List<Route> getApprovedRoutes() {
        return routeRepo.findApproved();
    }

    /**
     * Add a new route.  Origin is ALWAYS the operator's terminal city —
     * the UI must pass the operator's city, the service enforces it matches terminalId.
     */
    public int addRoute(int terminalId, String origin, String destination, int distanceKm) {
        int routeId = routeRepo.createRoute(terminalId, origin, destination, distanceKm);
        return routeId;
    }

    public boolean editRoute(int routeId, String origin, String destination, int distanceKm) {
        return routeRepo.updateRoute(routeId, origin, destination, distanceKm);
    }

    // ── Inter-terminal route requests ─────────────────────────

    public boolean sendRouteRequest(int routeId, int requestingOperatorId, String destinationCity) {
        return sendRouteRequest(routeId, requestingOperatorId, destinationCity, null, null);
    }

    public boolean sendRouteRequest(int routeId, int requestingOperatorId, String destinationCity,
                                    String requestedDepartureTime, String requestedArrivalTime) {
        return sendRouteRequest(routeId, requestingOperatorId, destinationCity,
                requestedDepartureTime, requestedArrivalTime, 0, 0, null) == null;
    }

    public String sendRouteRequest(int routeId, int requestingOperatorId, String destinationCity,
                                   String requestedDepartureTime, String requestedArrivalTime,
                                   int plannedDriverId, int plannedBusId, BigDecimal plannedPrice) {
        if (!routeRepo.existsById(routeId)) return "Route not found.";
        int destTerminalId = routeRepo.findTerminalIdByCity(destinationCity);
        if (destTerminalId == -1) return "Destination terminal was not found.";

        int receivingOperatorId = routeRepo.findOperatorByTerminal(destTerminalId);
        if (receivingOperatorId == -1) return "Destination terminal has no operator.";

        int originTerminalId = routeOriginTerminal(routeId);
        if (originTerminalId <= 0) return "Route origin is invalid.";
        if (plannedDriverId <= 0 || findDriverById(plannedDriverId, originTerminalId) == null) {
            return "Selected driver is not assigned to the origin terminal.";
        }
        Map<String, Object> bus = findBusById(plannedBusId, originTerminalId);
        if (plannedBusId <= 0 || bus == null) {
            return "Selected bus is not assigned to the origin terminal.";
        }
        if (!isDriverAvailable(plannedDriverId, requestedDepartureTime, requestedArrivalTime, 0)) {
            return "Selected driver is no longer available for that time.";
        }
        if (!isBusAvailable(plannedBusId, requestedDepartureTime, requestedArrivalTime, 0)) {
            return "Selected bus is no longer available for that time.";
        }
        if (plannedPrice == null || plannedPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return "Price must be greater than zero.";
        }

        int requestId = routeRepo.sendRouteRequest(
                routeId, requestingOperatorId, receivingOperatorId, null,
                requestedDepartureTime, requestedArrivalTime,
                plannedDriverId, plannedBusId, plannedPrice);

        if (requestId != -1) {
            adminRepo.sendNotificationToUser(
                    receivingOperatorId,
                    "New route request received. Please review and approve or reject it."
            );
        }

        return requestId != -1 ? null : "Failed to send route request.";
    }

    public List<RouteRequest> getIncomingRequests(int terminalId) {
        return routeRepo.findIncomingRequests(terminalId);
    }

    public List<RouteRequest> getOutgoingRequests(int terminalId) {
        return routeRepo.findOutgoingRequests(terminalId);
    }

    public String approveRequest(int requestId) {
        Map<String, Object> request = findRouteRequestForScheduling(requestId);
        if (request == null) return "Request not found.";

        String status = String.valueOf(request.get("status")).toLowerCase();
        if ("rejected".equals(status)) return "Rejected requests cannot be approved.";

        int existingScheduledTripId = intValue(request.get("scheduled_trip_id"));
        if (existingScheduledTripId > 0) {
            if (!routeRepo.approveRequest(requestId)) return "Failed.";
            return null;
        }

        String departureTime = stringValue(request.get("requested_departure_time"));
        String arrivalTime = stringValue(request.get("requested_arrival_time"));
        if (departureTime == null || arrivalTime == null) {
            if (!routeRepo.approveRequest(requestId)) return "Failed.";
            return null;
        }

        int routeId = intValue(request.get("route_id"));
        int originTerminalId = intValue(request.get("origin_id"));
        if (routeId <= 0 || originTerminalId <= 0) return "Request route is invalid.";

        int existingTripId = findExistingTripId(routeId, departureTime);
        if (existingTripId > 0) {
            if (!routeRepo.approveRequest(requestId)) return "Failed.";
            routeRepo.markRequestScheduledTrip(requestId, existingTripId);
            return null;
        }

        LocalDate date = LocalDate.parse(datePart(departureTime));
        if (!adminRepo.hasTerminalCapacity(originTerminalId, date.toString())) {
            return "Origin terminal has no capacity for another trip on this date.";
        }

        int plannedDriverId = intValue(request.get("planned_driver_id"));
        int plannedBusId = intValue(request.get("planned_bus_id"));
        BigDecimal plannedPrice = (BigDecimal) request.get("planned_price");
        if (plannedDriverId <= 0 || plannedBusId <= 0) {
            return "This request has no assigned driver or bus. Ask the origin operator to send it again.";
        }

        Map<String, Object> driver = findDriverById(plannedDriverId, originTerminalId);
        if (driver == null) return "Assigned driver is not active at the origin terminal.";
        if (!isDriverAvailable(plannedDriverId, departureTime, arrivalTime, 0)) {
            return "Assigned driver is no longer available for that time.";
        }

        Map<String, Object> bus = findBusById(plannedBusId, originTerminalId);
        if (bus == null) return "Assigned bus is not active at the origin terminal.";
        if (!isBusAvailable(plannedBusId, departureTime, arrivalTime, 0)) {
            return "Assigned bus is no longer available for that time.";
        }

        Trip trip = new Trip();
        trip.setRouteId(routeId);
        trip.setBusId(intValue(bus.get("id")));
        trip.setDriverId(intValue(driver.get("id")));
        trip.setTerminalId(originTerminalId);
        trip.setTripDate(date);
        trip.setDepartureTime(departureTime);
        trip.setArrivalTime(arrivalTime);
        int seats = intValue(bus.get("seatCount"));
        trip.setTotalSeats(seats);
        trip.setAvailableSeats(seats);
        trip.setPrice(plannedPrice != null && plannedPrice.compareTo(BigDecimal.ZERO) > 0
                ? plannedPrice
                : priceForDistance(intValue(request.get("distance_km"))));
        trip.setStatus("scheduled");

        int tripId = tripRepo.createTrip(trip);
        if (tripId <= 0) return "Failed to create the approved trip.";

        if (!routeRepo.approveRequest(requestId)) return "Trip was created, but request approval failed.";
        routeRepo.markRequestScheduledTrip(requestId, tripId);
        notifyTripDriver(tripId, "Route request approved. A trip has been assigned to you.");
        return null;
    }

    private Map<String, Object> findRouteRequestForScheduling(int requestId) {
        routeRepo.ensureRequestTripColumns();
        String sql = """
                SELECT rr.id, rr.route_id, rr.status, rr.scheduled_trip_id,
                       COALESCE(tr_source.departure_time, rr.requested_departure_time) AS requested_departure_time,
                       COALESCE(tr_source.arrival_time, rr.requested_arrival_time) AS requested_arrival_time,
                       r.origin_id, r.destination_id, r.distance_km,
                       rr.planned_driver_id, rr.planned_bus_id, rr.planned_price
                FROM route_requests rr
                JOIN routes r ON r.id = rr.route_id
                LEFT JOIN trips tr_source ON tr_source.id = rr.source_trip_id
                WHERE rr.id = ?
                LIMIT 1
                """;
        try (Connection conn = conn(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, requestId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getInt("id"));
            row.put("route_id", rs.getInt("route_id"));
            row.put("status", rs.getString("status"));
            row.put("scheduled_trip_id", rs.getInt("scheduled_trip_id"));
            row.put("requested_departure_time", rs.getString("requested_departure_time"));
            row.put("requested_arrival_time", rs.getString("requested_arrival_time"));
            row.put("origin_id", rs.getInt("origin_id"));
            row.put("destination_id", rs.getInt("destination_id"));
            row.put("distance_km", rs.getInt("distance_km"));
            row.put("planned_driver_id", rs.getInt("planned_driver_id"));
            row.put("planned_bus_id", rs.getInt("planned_bus_id"));
            row.put("planned_price", rs.getBigDecimal("planned_price"));
            return row;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    private int findExistingTripId(int routeId, String departureTime) {
        String sql = """
                SELECT id
                FROM trips
                WHERE route_id = ?
                  AND departure_time = ?
                  AND LOWER(status) <> 'cancelled'
                LIMIT 1
                """;
        try (Connection conn = conn(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, routeId);
            ps.setString(2, departureTime);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("id");
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    private BigDecimal priceForDistance(int distanceKm) {
        if (distanceKm <= 40) return BigDecimal.valueOf(250);
        if (distanceKm <= 95) return BigDecimal.valueOf(400);
        if (distanceKm <= 150) return BigDecimal.valueOf(500);
        if (distanceKm <= 197) return BigDecimal.valueOf(600);
        if (distanceKm <= 260) return BigDecimal.valueOf(750);
        return BigDecimal.valueOf(900);
    }

    private int intValue(Object value) {
        if (value == null) return 0;
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(value.toString()); }
        catch (NumberFormatException e) { return 0; }
    }

    private String stringValue(Object value) {
        if (value == null) return null;
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    public boolean rejectRequest(int requestId, String reason) {
        return routeRepo.rejectRequest(requestId, reason);
    }

    // ── Drivers ───────────────────────────────────────────────

    public List<Driver> getDriversForTerminal(int terminalId) {
        return driverRepo.findByTerminal(terminalId);
    }

    public List<Map<String, Object>> getDriverAvailability(int terminalId) {
        String sql = """
                SELECT u.id, u.full_name, u.email,
                       tr.id AS trip_id,
                       COALESCE(NULLIF(t_orig.name, ''), t_orig.city) AS origin,
                       COALESCE(NULLIF(t_dest.name, ''), t_dest.city) AS destination,
                       tr.departure_time,
                       tr.status
                FROM users u
                LEFT JOIN trips tr
                       ON tr.driver_id = u.id
                      AND LOWER(tr.status) IN ('scheduled', 'departed')
                LEFT JOIN terminals t_orig ON tr.origin_terminal_id = t_orig.id
                LEFT JOIN terminals t_dest ON tr.destination_terminal_id = t_dest.id
                WHERE u.role = 'driver'
                  AND u.terminal_id = ?
                  AND u.is_active = TRUE
                ORDER BY u.full_name, tr.departure_time
                """;
        Map<Integer, Map<String, Object>> rows = new LinkedHashMap<>();
        try (Connection conn = al.albus.config.DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, terminalId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                int driverId = rs.getInt("id");
                Map<String, Object> row = rows.computeIfAbsent(driverId, id -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", id);
                    try {
                        item.put("fullName", rs.getString("full_name"));
                        item.put("email", rs.getString("email"));
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                    return item;
                });
                if (rs.getObject("trip_id") != null && !row.containsKey("activeTripId")) {
                    row.put("activeTripId", rs.getInt("trip_id"));
                    row.put("activeTrip", rs.getString("origin") + " to " + rs.getString("destination"));
                    row.put("activeTripDeparture", rs.getString("departure_time"));
                    row.put("activeTripStatus", rs.getString("status"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        rows.values().forEach(row -> {
            boolean active = row.containsKey("activeTripId");
            row.put("availability", active ? "In Service" : "Available");
            row.put("activeTrips", active ? 1 : 0);
        });
        return new ArrayList<>(rows.values());
    }

    public List<Map<String, Object>> getTerminalBusAvailability(int terminalId, String date) {
        return busRepo.availabilityForTerminal(terminalId);
    }

    public Map<String, Object> updateBusStatus(int busId, int terminalId, String status) {
        String storedStatus = normalizeBusStatus(status);
        boolean updated = busRepo.updateStatusForTerminal(busId, terminalId, storedStatus);
        if (!updated) {
            return Map.of("success", false, "message", "Failed to update bus status.");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Bus status updated.");
        result.put("reassignedTrips", 0);
        result.put("flaggedTrips", 0);

        if ("maintenance".equals(storedStatus)) {
            result.putAll(reassignTripsFromUnavailableBus(busId, terminalId));
        }
        return result;
    }

    public Map<String, Object> evaluateOverflowTrip(int tripId, int terminalId) {
        Trip trip = tripRepo.findById(tripId);
        if (trip == null || trip.getTerminalId() != terminalId) {
            return Map.of("canDispatch", false, "message", "Trip not found.");
        }
        if (trip.getAvailableSeats() > 0) {
            return Map.of("canDispatch", false, "message", "This trip still has available seats.");
        }

        int destinationTerminalId = tripRepo.findDestinationTerminalIdById(tripId);
        boolean destinationHasCapacity = hasDestinationParkingCapacity(destinationTerminalId, datePart(trip.getArrivalTime()));
        Map<String, Object> driver = findAvailableDriver(terminalId, trip.getDepartureTime(), trip.getArrivalTime());
        if (driver == null) driver = findDriverById(trip.getDriverId(), terminalId);
        Map<String, Object> bus = findAvailableBus(terminalId, trip.getDepartureTime(), trip.getArrivalTime());
        boolean driverAvailable = driver != null;
        boolean busAvailable = bus != null;
        boolean canDispatch = destinationHasCapacity && driverAvailable && busAvailable;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("canDispatch", canDispatch);
        result.put("destinationHasCapacity", destinationHasCapacity);
        result.put("driverAvailable", driverAvailable);
        result.put("busAvailable", busAvailable);
        result.put("driver", driver);
        result.put("bus", bus);
        result.put("trip", Map.of(
                "id", trip.getId(),
                "route", trip.getOrigin() + " to " + trip.getDestination(),
                "departureTime", trip.getDepartureTime(),
                "arrivalTime", trip.getArrivalTime()
        ));
        if (!destinationHasCapacity) result.put("message", "Destination terminal has no parking capacity for another bus.");
        else if (!driverAvailable) result.put("message", "No available drivers at the origin terminal.");
        else if (!busAvailable) result.put("message", "No available buses at the origin terminal.");
        else result.put("message", "Extra trip can be dispatched.");
        return result;
    }

    public int dispatchOverflowTrip(int tripId, int terminalId) {
        Map<String, Object> check = evaluateOverflowTrip(tripId, terminalId);
        if (!Boolean.TRUE.equals(check.get("canDispatch"))) return -1;

        Trip original = tripRepo.findById(tripId);
        @SuppressWarnings("unchecked")
        Map<String, Object> driver = (Map<String, Object>) check.get("driver");
        @SuppressWarnings("unchecked")
        Map<String, Object> bus = (Map<String, Object>) check.get("bus");
        Trip extra = new Trip();
        extra.setRouteId(original.getRouteId());
        extra.setBusId(Integer.parseInt(bus.get("id").toString()));
        extra.setDriverId(Integer.parseInt(driver.get("id").toString()));
        extra.setTerminalId(terminalId);
        extra.setTripDate(LocalDate.parse(datePart(original.getDepartureTime())));
        extra.setDepartureTime(original.getDepartureTime());
        extra.setArrivalTime(original.getArrivalTime());
        int seats = busRepo.seatCount(extra.getBusId());
        extra.setTotalSeats(seats);
        extra.setAvailableSeats(seats);
        extra.setPrice(original.getPrice());
        extra.setStatus("scheduled");

        int newTripId = tripRepo.createTrip(extra);
        if (newTripId != -1) {
            notifyTripDriver(newTripId, "Operator dispatched an extra trip because the original trip reached full capacity.");
        }
        return newTripId;
    }

    private void notifyTripDriver(int tripId, String message) {
        String sql = """
                INSERT INTO notifications (user_id, trip_id, message)
                SELECT driver_id, ?, ?
                FROM trips
                WHERE id = ?
                  AND driver_id IS NOT NULL
                """;
        try (Connection conn = al.albus.config.DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ps.setString(2, message);
            ps.setInt(3, tripId);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public String requestOverflowTrip(int tripId, int terminalId, int requestingOperatorId) {
        Map<String, Object> check = evaluateOverflowTrip(tripId, terminalId);
        if (!Boolean.TRUE.equals(check.get("canDispatch"))) {
            Object message = check.get("message");
            return message == null ? "Route request cannot be sent." : message.toString();
        }

        Trip original = tripRepo.findById(tripId);
        if (original == null) return "Trip not found.";
        int destinationTerminalId = tripRepo.findDestinationTerminalIdById(tripId);
        int receivingOperatorId = routeRepo.findOperatorByTerminal(destinationTerminalId);
        if (receivingOperatorId == -1) return "Destination terminal has no operator to receive this request.";
        int routeId = routeRepo.findOrCreateRoute(terminalId, destinationTerminalId);
        if (routeId == -1) return "Failed to resolve route for this terminal.";

        int requestId = routeRepo.sendRouteRequest(routeId, requestingOperatorId, receivingOperatorId, tripId);
        if (requestId == -1) return "Failed to send route request.";

        adminRepo.sendNotificationToUser(
                receivingOperatorId,
                "Extra trip request received for " + original.getOrigin() + " to " + original.getDestination() + ". Please approve or reject it."
        );
        return null;
    }

    public boolean addDriver(String fullName, String email, String plainPassword, int terminalId) {
        String hash = pwdSvc.hash(plainPassword);
        return driverRepo.createDriver(fullName, email, hash, terminalId);
    }

    private int getTerminalCapacity(int terminalId) {
        String sql = """
                SELECT COALESCE(tc.max_buses_day, 0) AS max_buses_day
                FROM terminals t
                LEFT JOIN terminal_capacity tc ON tc.terminal_id = t.id
                WHERE t.id = ?
                """;
        try (Connection conn = al.albus.config.DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, terminalId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("max_buses_day");
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    private boolean hasDestinationParkingCapacity(int terminalId, String date) {
        if (terminalId <= 0 || date == null || date.isBlank()) return false;
        int maxBuses = getTerminalCapacity(terminalId);
        if (maxBuses <= 0) return true;
        String sql = """
                SELECT COUNT(*) AS arriving_buses
                FROM trips
                WHERE destination_terminal_id = ?
                  AND DATE(arrival_time) = ?
                  AND LOWER(status) <> 'cancelled'
                """;
        try (Connection conn = al.albus.config.DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, terminalId);
            ps.setString(2, date);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("arriving_buses") < maxBuses;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    private Map<String, Object> findAvailableDriver(int terminalId, String departureTime, String arrivalTime) {
        String sql = """
                SELECT u.id, u.full_name, u.email
                FROM users u
                WHERE u.role = 'driver'
                  AND u.terminal_id = ?
                  AND u.is_active = TRUE
                  AND NOT EXISTS (
                      SELECT 1
                      FROM trips tr
                      WHERE tr.driver_id = u.id
                        AND LOWER(tr.status) IN ('scheduled', 'departed')
                        AND tr.departure_time < ?
                        AND tr.arrival_time > ?
                  )
                ORDER BY u.full_name
                LIMIT 1
                """;
        try (Connection conn = al.albus.config.DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, terminalId);
            ps.setString(2, arrivalTime);
            ps.setString(3, departureTime);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getInt("id"));
                row.put("fullName", rs.getString("full_name"));
                row.put("email", rs.getString("email"));
                return row;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    private Map<String, Object> findDriverById(int driverId, int terminalId) {
        String sql = """
                SELECT id, full_name, email
                FROM users
                WHERE id = ?
                  AND terminal_id = ?
                  AND role = 'driver'
                  AND is_active = TRUE
                """;
        try (Connection conn = al.albus.config.DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, driverId);
            ps.setInt(2, terminalId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getInt("id"));
                row.put("fullName", rs.getString("full_name"));
                row.put("email", rs.getString("email"));
                return row;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    private int routeOriginTerminal(int routeId) {
        String sql = "SELECT origin_id FROM routes WHERE id = ? LIMIT 1";
        try (Connection conn = al.albus.config.DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, routeId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("origin_id");
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    private Map<String, Object> findBusById(int busId, int terminalId) {
        String sql = """
                SELECT id, plate_number, model, seat_count
                FROM buses
                WHERE id = ?
                  AND terminal_id = ?
                  AND is_active = TRUE
                  AND LOWER(COALESCE(status, 'available')) = 'available'
                LIMIT 1
                """;
        try (Connection conn = al.albus.config.DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, busId);
            ps.setInt(2, terminalId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getInt("id"));
                row.put("plateNumber", rs.getString("plate_number"));
                row.put("model", rs.getString("model"));
                row.put("seatCount", rs.getInt("seat_count"));
                return row;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    private boolean isDriverAvailable(int driverId, String departureTime, String arrivalTime, int excludeTripId) {
        String sql = """
                SELECT NOT EXISTS (
                    SELECT 1
                    FROM trips tr
                    WHERE tr.driver_id = ?
                      AND tr.id <> ?
                      AND LOWER(tr.status) IN ('scheduled', 'departed')
                      AND tr.departure_time < ?
                      AND tr.arrival_time > ?
                ) AS available
                """;
        try (Connection conn = al.albus.config.DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, driverId);
            ps.setInt(2, excludeTripId);
            ps.setString(3, arrivalTime);
            ps.setString(4, departureTime);
            ResultSet rs = ps.executeQuery();
            return rs.next() && rs.getBoolean("available");
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean isBusAvailable(int busId, String departureTime, String arrivalTime, int excludeTripId) {
        String sql = """
                SELECT EXISTS (
                    SELECT 1
                    FROM buses b
                    WHERE b.id = ?
                      AND b.is_active = TRUE
                      AND LOWER(COALESCE(b.status, 'available')) = 'available'
                ) AND NOT EXISTS (
                    SELECT 1
                    FROM trips tr
                    WHERE tr.bus_id = ?
                      AND tr.id <> ?
                      AND LOWER(tr.status) IN ('scheduled', 'departed')
                      AND tr.departure_time < ?
                      AND tr.arrival_time > ?
                ) AS available
                """;
        try (Connection conn = al.albus.config.DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, busId);
            ps.setInt(2, busId);
            ps.setInt(3, excludeTripId);
            ps.setString(4, arrivalTime);
            ps.setString(5, departureTime);
            ResultSet rs = ps.executeQuery();
            return rs.next() && rs.getBoolean("available");
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    private Map<String, Object> findAvailableBus(int terminalId, String departureTime, String arrivalTime) {
        String sql = """
                SELECT b.id, b.plate_number, b.model, b.seat_count
                FROM buses b
                WHERE b.terminal_id = ?
                  AND b.is_active = TRUE
                  AND LOWER(COALESCE(b.status, 'available')) = 'available'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM trips tr
                      WHERE tr.bus_id = b.id
                        AND LOWER(tr.status) IN ('scheduled', 'departed')
                        AND tr.departure_time < ?
                        AND tr.arrival_time > ?
                  )
                ORDER BY b.plate_number
                LIMIT 1
                """;
        try (Connection conn = al.albus.config.DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, terminalId);
            ps.setString(2, arrivalTime);
            ps.setString(3, departureTime);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getInt("id"));
                row.put("plateNumber", rs.getString("plate_number"));
                row.put("model", rs.getString("model"));
                row.put("seatCount", rs.getInt("seat_count"));
                return row;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    private String datePart(String value) {
        return value == null || value.length() < 10 ? LocalDate.now().toString() : value.substring(0, 10);
    }

    public List<Map<String, Object>> passengerContacts(int operatorId) {
        ensureContactSchema();
        String sql = """
                SELECT cm.id, cm.subject, cm.message, cm.reply, cm.status, cm.created_at, cm.replied_at,
                       u.full_name AS passenger_name, u.email AS passenger_email
                FROM contact_messages cm
                JOIN users u ON u.id = cm.passenger_id
                WHERE cm.operator_id = ?
                  AND COALESCE(cm.operator_deleted, FALSE) = FALSE
                ORDER BY cm.created_at DESC
                """;
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = al.albus.config.DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, operatorId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getInt("id"));
                row.put("subject", rs.getString("subject"));
                row.put("message", rs.getString("message"));
                row.put("reply", rs.getString("reply"));
                row.put("status", rs.getString("status"));
                row.put("createdAt", rs.getString("created_at"));
                row.put("repliedAt", rs.getString("replied_at"));
                row.put("passengerName", rs.getString("passenger_name"));
                row.put("passengerEmail", rs.getString("passenger_email"));
                rows.add(row);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return rows;
    }

    public String replyToPassengerContact(int messageId, int operatorId, String reply) {
        ensureContactSchema();
        if (reply == null || reply.isBlank()) return "Reply is required.";
        String findSql = "SELECT passenger_id FROM contact_messages WHERE id = ? AND operator_id = ?";
        String updateSql = """
                UPDATE contact_messages
                SET reply = ?, status = 'answered', replied_at = CURRENT_TIMESTAMP
                WHERE id = ? AND operator_id = ?
                """;
        try (Connection conn = al.albus.config.DatabaseConfig.getConnection()) {
            int passengerId;
            try (PreparedStatement find = conn.prepareStatement(findSql)) {
                find.setInt(1, messageId);
                find.setInt(2, operatorId);
                ResultSet rs = find.executeQuery();
                if (!rs.next()) return "Message not found.";
                passengerId = rs.getInt("passenger_id");
            }
            try (PreparedStatement update = conn.prepareStatement(updateSql)) {
                update.setString(1, reply.trim());
                update.setInt(2, messageId);
                update.setInt(3, operatorId);
                if (update.executeUpdate() != 1) return "Failed to save reply.";
            }
            adminRepo.sendNotificationToUser(passengerId, "Operator replied to your contact message: " + reply.trim());
            return null;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return "Failed to reply.";
    }

    // ── Bookings (operator read-only view) ────────────────────

    public String deletePassengerContact(int messageId, int operatorId) {
        ensureContactSchema();
        String sql = """
                UPDATE contact_messages
                SET operator_deleted = TRUE
                WHERE id = ? AND operator_id = ?
                """;
        try (Connection conn = al.albus.config.DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, messageId);
            ps.setInt(2, operatorId);
            return ps.executeUpdate() == 1 ? null : "Message not found.";
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return "Failed to delete message.";
    }

    private Map<String, Object> findTripTemplate(int templateId, int terminalId, int operatorId) {
        List<Map<String, Object>> templates = getTripTemplates(terminalId, operatorId);
        return templates.stream()
                .filter(t -> Integer.parseInt(t.get("id").toString()) == templateId)
                .findFirst()
                .orElse(null);
    }

    private boolean isDriverAtTerminal(int driverId, int terminalId) {
        String sql = """
                SELECT 1
                FROM users
                WHERE id = ?
                  AND terminal_id = ?
                  AND role = 'driver'
                  AND is_active = TRUE
                LIMIT 1
                """;
        try (Connection conn = al.albus.config.DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, driverId);
            ps.setInt(2, terminalId);
            return ps.executeQuery().next();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean isRouteApproved(int routeId) {
        return routeRepo.findApproved().stream().anyMatch(r -> r.getId() == routeId);
    }

    private boolean tripExists(int routeId, String departureTime) {
        String sql = """
                SELECT 1
                FROM trips
                WHERE route_id = ?
                  AND departure_time = ?
                  AND LOWER(status) <> 'cancelled'
                LIMIT 1
                """;
        try (Connection conn = al.albus.config.DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, routeId);
            ps.setString(2, departureTime);
            return ps.executeQuery().next();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean routeRequestExists(int routeId, int operatorId, String departureTime) {
        String sql = """
                SELECT 1
                FROM route_requests
                WHERE route_id = ?
                  AND requesting_operator = ?
                  AND requested_departure_time = ?
                  AND status IN ('pending', 'approved')
                LIMIT 1
                """;
        try (Connection conn = al.albus.config.DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, routeId);
            ps.setInt(2, operatorId);
            ps.setString(3, departureTime);
            return ps.executeQuery().next();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    private LocalDate parseWeekStart(String value) {
        LocalDate base = value == null || value.isBlank() ? LocalDate.now() : LocalDate.parse(value);
        return base.minusDays(base.getDayOfWeek().getValue() - 1L);
    }

    private String normalizeClock(String value) {
        if (value == null) return "";
        value = value.trim();
        if (value.length() >= 5) return value.substring(0, 5);
        return value;
    }

    private String normalizeDaysOfWeek(String daysOfWeek) {
        Set<Integer> days = parseDays(daysOfWeek);
        List<String> sorted = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            if (days.contains(day)) sorted.add(String.valueOf(day));
        }
        return String.join(",", sorted);
    }

    private Set<Integer> parseDays(String daysOfWeek) {
        Set<Integer> days = new HashSet<>();
        if (daysOfWeek == null) return days;
        for (String token : daysOfWeek.split(",")) {
            try {
                int day = Integer.parseInt(token.trim());
                if (day >= 1 && day <= 7) days.add(day);
            } catch (NumberFormatException ignored) {
            }
        }
        return days;
    }

    public List<String[]> getPassengerManifest(int tripId) {
        return tripRepo.getPassengerManifest(tripId);
    }

    public boolean tripStartsFromTerminal(int tripId, int terminalId) {
        return tripRepo.belongsToOriginTerminal(tripId, terminalId);
    }

    public String getTerminalCity(int terminalId) {
        return routeRepo.findTerminalCityById(terminalId);
    }

}
