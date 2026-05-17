package al.albus.service;

import al.albus.config.DatabaseConfig;
import al.albus.repository.TripRepository;

import java.sql.*;
import java.util.*;

public class DriverService {

    private final TripRepository tripRepo = new TripRepository();

    private Connection conn() throws SQLException {
        return DatabaseConfig.getConnection();
    }

    public Map<String, Object> getTerminalInfoForDriver(int driverId) {
        String sql = """
                SELECT t.id, t.city, COALESCE(NULLIF(t.name, ''), t.city) AS terminal_name
                FROM users u
                JOIN terminals t ON t.id = u.terminal_id
                WHERE u.id = ?
                  AND u.role = 'driver'
                LIMIT 1
                """;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, driverId);
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
        return Map.of("terminalName", "Terminal");
    }

    // ══════════════════════════════════════════════════════════
    //  TRIPS
    // ══════════════════════════════════════════════════════════

    /**
     * Returns all trips assigned to a driver, ordered by departure time.
     */
    public List<Map<String, Object>> getTripsForDriver(int driverId) {
        tripRepo.refreshStatusesByTime();
        resetFutureTrips(driverId);
        String sql = """
                SELECT t.id,
                       t_orig.city      AS origin,
                       t_dest.city      AS destination,
                       t.departure_time,
                       t.arrival_time,
                       t.total_seats,
                       t.available_seats,
                       (t.total_seats - t.available_seats) AS booked_seats,
                       t.price,
                       LOWER(t.status)  AS status
                FROM   trips      t
                JOIN   routes     r      ON t.route_id               = r.id
                JOIN   terminals  t_orig ON t.origin_terminal_id     = t_orig.id
                JOIN   terminals  t_dest ON t.destination_terminal_id = t_dest.id
                WHERE  t.driver_id = ?
                ORDER  BY t.departure_time ASC
                """;

        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, driverId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",             rs.getInt("id"));
                row.put("origin",         rs.getString("origin"));
                row.put("destination",    rs.getString("destination"));
                row.put("departureTime",  rs.getString("departure_time"));
                row.put("arrivalTime",    rs.getString("arrival_time"));
                row.put("totalSeats",     rs.getInt("total_seats"));
                row.put("availableSeats", rs.getInt("available_seats"));
                row.put("bookedSeats",    rs.getInt("booked_seats"));
                row.put("price",          rs.getBigDecimal("price"));
                row.put("status",         rs.getString("status"));
                result.add(row);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return result;
    }

    private void resetFutureTrips(int driverId) {
        String notDepartedSql = """
                UPDATE trips
                SET status = 'scheduled'
                WHERE driver_id = ?
                  AND departure_time > NOW()
                  AND LOWER(status) IN ('departed', 'arrived')
                """;
        String notArrivedSql = """
                UPDATE trips
                SET status = 'departed'
                WHERE driver_id = ?
                  AND departure_time <= NOW()
                  AND arrival_time > NOW()
                  AND LOWER(status) = 'arrived'
                """;
        try (Connection c = conn();
             PreparedStatement notDeparted = c.prepareStatement(notDepartedSql);
             PreparedStatement notArrived = c.prepareStatement(notArrivedSql)) {
            notDeparted.setInt(1, driverId);
            notDeparted.executeUpdate();
            notArrived.setInt(1, driverId);
            notArrived.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * Verifies a trip belongs to this driver.
     */
    public boolean tripBelongsToDriver(int tripId, int driverId) {
        String sql = "SELECT 1 FROM trips WHERE id = ? AND driver_id = ?";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ps.setInt(2, driverId);
            return ps.executeQuery().next();
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    // ══════════════════════════════════════════════════════════
    //  STATUS TRANSITIONS
    // ══════════════════════════════════════════════════════════

    /**
     * Transitions trip status: scheduled → departed, or departed → arrived.
     *
     * IMPORTANT: Passenger count does NOT gate departure.
     * A driver can mark a trip departed with 0, 1, 2, or any number of passengers.
     * Low-passenger handling is a separate admin/operator concern and must NOT
     * be mixed into the departure logic.
     *
     * The only requirements are:
     *   1. The status transition is valid (scheduled→departed or departed→arrived).
     *   2. The trip is currently in the expected state (optimistic check prevents
     *      double-firing without needing a transaction lock).
     *
     * @param tripId          the trip to transition
     * @param expectedCurrent current status that must be present for the update to apply
     * @param target          the new status to set
     * @return true if the row was updated, false if the trip was not in the expected state
     */
    public boolean transitionStatus(int tripId, String expectedCurrent, String target) {
        tripRepo.refreshStatusesByTime();

        expectedCurrent = expectedCurrent.toLowerCase().trim();
        target = target.toLowerCase().trim();

        boolean validTransition =
                ("scheduled".equals(expectedCurrent) && "departed".equals(target))
                        || ("departed".equals(expectedCurrent) && "arrived".equals(target));

        if (!validTransition) return false;

        String timeGate = "departed".equals(target)
                ? " AND departure_time <= NOW()"
                : " AND arrival_time <= NOW()";

        String sql = """
                UPDATE trips
                SET status = ?
                WHERE id = ?
                  AND LOWER(status) = ?
                """ + timeGate;

        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, target);
            ps.setInt(2, tripId);
            ps.setString(3, expectedCurrent);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    // ══════════════════════════════════════════════════════════
    //  PASSENGER MANIFEST
    // ══════════════════════════════════════════════════════════

    /**
     * Full passenger manifest for a trip.
     * Each String[9]: [bookingId, fullName, seat, age, discount, price, specialNeeds, paymentMethod, paymentStatus]
     */
    public List<String[]> getPassengerManifest(int tripId) {
        String sql = """
                SELECT b.id AS booking_id,
                       CONCAT(pd.first_name, ' ', pd.last_name) AS full_name,
                       pd.seat_number,
                       pd.age,
                       CONCAT(pd.discount_pct, '%')             AS discount,
                       CONCAT(pd.final_price, ' L')             AS price,
                       CASE WHEN pd.special_needs = 1 THEN 'Yes' ELSE 'None' END AS special_needs,
                       b.payment_method,
                       b.payment_status
                FROM   bookings         b
                JOIN   passengers_detail pd ON pd.booking_id = b.id
                WHERE  b.trip_id = ?
                  AND  b.payment_status <> 'cancelled'
                ORDER  BY pd.seat_number
                """;

        List<String[]> result = new ArrayList<>();
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(new String[]{
                        String.valueOf(rs.getInt("booking_id")),
                        rs.getString("full_name"),
                        rs.getString("seat_number"),
                        rs.getString("age"),
                        rs.getString("discount"),
                        rs.getString("price"),
                        rs.getString("special_needs"),
                        rs.getString("payment_method"),
                        rs.getString("payment_status")
                });
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return result;
    }

    // ══════════════════════════════════════════════════════════
    //  CASH PAYMENTS
    // ══════════════════════════════════════════════════════════

    /**
     * Returns terminal-payment passengers for a trip.
     * Each Object[5]: [bookingId, passengerName, seatNumber, amountDue, paymentStatus]
     */
    public List<Object[]> getCashPassengers(int tripId) {
        String sql = """
                SELECT b.id                                      AS booking_id,
                       CONCAT(pd.first_name, ' ', pd.last_name) AS passenger_name,
                       pd.seat_number,
                       pd.final_price,
                       b.payment_status
                FROM   bookings         b
                JOIN   passengers_detail pd ON pd.booking_id = b.id
                WHERE  b.trip_id       = ?
                  AND  b.payment_method = 'terminal'
                  AND  b.payment_status = 'pending'
                ORDER  BY b.payment_status DESC, pd.seat_number
                """;

        List<Object[]> result = new ArrayList<>();
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(new Object[]{
                        rs.getInt("booking_id"),
                        rs.getString("passenger_name"),
                        rs.getString("seat_number"),
                        rs.getBigDecimal("final_price"),
                        rs.getString("payment_status")
                });
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return result;
    }

    /**
     * Verifies a booking sits on one of this driver's trips.
     */
    public boolean bookingBelongsToDriver(int bookingId, int driverId) {
        String sql = """
                SELECT 1 FROM bookings b
                JOIN   trips t ON b.trip_id = t.id
                WHERE  b.id        = ?
                  AND  t.driver_id = ?
                """;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, bookingId);
            ps.setInt(2, driverId);
            return ps.executeQuery().next();
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Marks a terminal-payment booking as paid. Only updates if currently pending.
     */
    public boolean markBookingPaid(int bookingId) {
        String sql = """
                UPDATE bookings
                SET    payment_status = 'paid'
                WHERE  id             = ?
                  AND  payment_method = 'terminal'
                  AND  payment_status = 'pending'
                """;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, bookingId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean removePendingBooking(int bookingId) {
        ensureBookingStatusSupportsCancelled();
        String findSql = """
                SELECT trip_id
                FROM bookings
                WHERE id = ?
                  AND LOWER(payment_status) = 'pending'
                """;
        String cancelSql = """
                UPDATE bookings
                SET payment_status = 'cancelled'
                WHERE id = ?
                  AND LOWER(payment_status) = 'pending'
                """;
        String releaseSeatSql = """
                UPDATE trips
                SET available_seats = LEAST(total_seats, available_seats + 1)
                WHERE id = ?
                """;

        try (Connection c = conn()) {
            c.setAutoCommit(false);
            try {
                int tripId;
                try (PreparedStatement find = c.prepareStatement(findSql)) {
                    find.setInt(1, bookingId);
                    ResultSet rs = find.executeQuery();
                    if (!rs.next()) {
                        c.rollback();
                        return false;
                    }
                    tripId = rs.getInt("trip_id");
                }
                try (PreparedStatement cancel = c.prepareStatement(cancelSql)) {
                    cancel.setInt(1, bookingId);
                    if (cancel.executeUpdate() != 1) {
                        c.rollback();
                        return false;
                    }
                }
                try (PreparedStatement release = c.prepareStatement(releaseSeatSql)) {
                    release.setInt(1, tripId);
                    release.executeUpdate();
                }
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    private void ensureBookingStatusSupportsCancelled() {
        String sql = """
                ALTER TABLE bookings
                MODIFY payment_status ENUM('paid','pending','cancelled') DEFAULT 'pending'
                """;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
