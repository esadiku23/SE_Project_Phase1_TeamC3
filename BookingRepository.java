package al.albus.repository;

import al.albus.config.DatabaseConfig;
import al.albus.model.Booking;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class BookingRepository {

    /**
     * Insert a row into bookings, return the generated booking ID.
     */
    public int createBooking(int passengerId, int tripId,
                             String paymentMethod, String paymentStatus) {
        String sql = "INSERT INTO bookings (passenger_id, trip_id, payment_method, payment_status) " +
                "VALUES (?, ?, ?, ?)";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            ps.setInt(1, passengerId);
            ps.setInt(2, tripId);
            ps.setString(3, paymentMethod);
            ps.setString(4, paymentStatus);
            ps.executeUpdate();

            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) return keys.getInt(1);

        } catch (SQLException e) {
            e.printStackTrace();
        }
        return -1;  // indicates failure
    }

    /**
     * Insert a row into passengers_detail for a booking.
     */
    public boolean createPassengerDetail(int bookingId, String firstName, String lastName,
                                         String phone, int age, int seatNumber,
                                         int discountPct, java.math.BigDecimal finalPrice,
                                         boolean specialNeeds) {
        String sql = "INSERT INTO passengers_detail " +
                "(booking_id, first_name, last_name, phone, age, seat_number, " +
                " discount_pct, final_price, special_needs) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, bookingId);
            ps.setString(2, firstName);
            ps.setString(3, lastName);
            ps.setString(4, phone);
            ps.setInt(5, age);
            ps.setInt(6, seatNumber);
            ps.setInt(7, discountPct);
            ps.setBigDecimal(8, finalPrice);
            ps.setBoolean(9, specialNeeds);

            return ps.executeUpdate() == 1;

        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Decrement available_seats on the trip by the number of tickets booked.
     */
    public boolean decrementSeats(int tripId, int count) {
        String sql = "UPDATE trips SET available_seats = available_seats - ? " +
                "WHERE id = ? AND available_seats >= ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, count);
            ps.setInt(2, tripId);
            ps.setInt(3, count);  // guard: only update if enough seats exist

            return ps.executeUpdate() == 1;

        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Get all taken seat numbers for a trip (to avoid reassigning them).
     */
    public List<Integer> getTakenSeats(int tripId) {
        String sql = "SELECT pd.seat_number FROM passengers_detail pd " +
                "JOIN bookings b ON pd.booking_id = b.id " +
                "WHERE b.trip_id = ? " +
                "AND LOWER(COALESCE(b.payment_status, '')) <> 'cancelled'";

        List<Integer> taken = new ArrayList<>();

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, tripId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) taken.add(rs.getInt("seat_number"));

        } catch (SQLException e) {
            e.printStackTrace();
        }
        return taken;
    }

    /**
     * Get total seats for a trip.
     */
    public int getTotalSeats(int tripId) {
        String sql = "SELECT total_seats FROM trips WHERE id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, tripId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("total_seats");

        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    /**
     * Update payment status for a booking (e.g. after card payment succeeds).
     */
    public boolean updatePaymentStatus(int bookingId, String newStatus) {
        String sql = "UPDATE bookings SET payment_status = ? WHERE id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, newStatus);
            ps.setInt(2, bookingId);
            return ps.executeUpdate() == 1;

        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }
    // ── ADD THESE METHODS TO BookingRepository.java ──────────────

    /**
     * Full booking history for a passenger.
     * Returns: [route, date, departure, seat, discount, finalPrice, paymentMethod, paymentStatus]
     */
    public List<String[]> getBookingHistory(int passengerId) {
        String sql =
                "SELECT t_orig.city AS origin, t_dest.city AS destination, " +
                        "       tr.departure_time, pd.seat_number, " +
                        "       pd.discount_pct, pd.final_price, " +
                        "       b.payment_method, b.payment_status, " +
                        "       b.id AS booking_id, tr.status AS trip_status " +
                        "FROM bookings b " +
                        "JOIN trips tr              ON b.trip_id       = tr.id " +
                        "JOIN routes r              ON tr.route_id     = r.id " +
                        "JOIN terminals t_orig      ON r.origin_id     = t_orig.id " +
                        "JOIN terminals t_dest      ON r.destination_id= t_dest.id " +
                        "JOIN passengers_detail pd  ON pd.booking_id   = b.id " +
                        "WHERE b.passenger_id = ? " +
                        "ORDER BY tr.departure_time DESC";

        List<String[]> rows = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, passengerId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                rows.add(new String[]{
                        rs.getString("origin") + " → " + rs.getString("destination"),
                        rs.getTimestamp("departure_time").toLocalDateTime()
                                .toLocalDate().toString(),
                        rs.getTimestamp("departure_time").toLocalDateTime()
                                .toLocalTime().toString().substring(0, 5),
                        rs.getString("seat_number"),
                        rs.getString("discount_pct") + "%",
                        rs.getString("final_price") + " L",
                        rs.getString("payment_method"),
                        rs.getString("payment_status"),
                        rs.getString("booking_id"),
                        rs.getString("trip_status")
                });
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return rows;
    }

    /**
     * E-ticket details for all bookings of a passenger.
     * Returns same structure as booking history — used to render ticket cards.
     */
    public List<String[]> getTicketsForPassenger(int passengerId) {
        // Same query — tickets are just confirmed (paid) bookings
        String sql =
                "SELECT t_orig.city AS origin, t_dest.city AS destination, " +
                        "       tr.departure_time, tr.arrival_time, " +
                        "       pd.seat_number, pd.first_name, pd.last_name, " +
                        "       pd.discount_pct, pd.final_price, pd.special_needs, " +
                        "       b.payment_method, b.payment_status, b.id AS booking_id " +
                        "FROM bookings b " +
                        "JOIN trips tr              ON b.trip_id        = tr.id " +
                        "JOIN routes r              ON tr.route_id      = r.id " +
                        "JOIN terminals t_orig      ON r.origin_id      = t_orig.id " +
                        "JOIN terminals t_dest      ON r.destination_id = t_dest.id " +
                        "JOIN passengers_detail pd  ON pd.booking_id    = b.id " +
                        "WHERE b.passenger_id = ? " +
                        "ORDER BY tr.departure_time DESC";

        List<String[]> rows = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, passengerId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Timestamp dep = rs.getTimestamp("departure_time");
                Timestamp arr = rs.getTimestamp("arrival_time");
                rows.add(new String[]{
                        rs.getString("origin") + " → " + rs.getString("destination"),
                        dep.toLocalDateTime().toLocalDate().toString(),
                        dep.toLocalDateTime().toLocalTime().toString().substring(0, 5),
                        arr != null ? arr.toLocalDateTime().toLocalTime()
                                .toString().substring(0, 5) : "—",
                        rs.getString("seat_number"),
                        rs.getString("first_name") + " " + rs.getString("last_name"),
                        rs.getString("discount_pct") + "%",
                        rs.getString("final_price") + " L",
                        rs.getBoolean("special_needs") ? "Yes" : "No",
                        rs.getString("payment_method"),
                        rs.getString("payment_status"),
                        rs.getString("booking_id")
                });
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return rows;
    }
}
