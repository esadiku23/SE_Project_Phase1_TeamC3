package al.albus.service;

import al.albus.config.DatabaseConfig;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.util.*;

public class PassengerService {
    private final al.albus.repository.BusRepository busRepo = new al.albus.repository.BusRepository();

    private Connection conn() throws SQLException {
        return DatabaseConfig.getConnection();
    }

    private void ensureContactSchema() {
        ensureColumn("terminals", "phone",
                "ALTER TABLE terminals ADD COLUMN phone VARCHAR(30) NULL");
        String seedPhones = """
                UPDATE terminals
                SET phone = CONCAT('+355 4 220 ', LPAD(id, 4, '0'))
                WHERE phone IS NULL OR phone = ''
                """;
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
        String createNotifications = """
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
        try (Connection c = conn();
             PreparedStatement seed = c.prepareStatement(seedPhones);
             PreparedStatement create = c.prepareStatement(createMessages);
             PreparedStatement createNotif = c.prepareStatement(createNotifications)) {
            seed.executeUpdate();
            create.executeUpdate();
            createNotif.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        ensureColumn("contact_messages", "operator_deleted",
                "ALTER TABLE contact_messages ADD COLUMN operator_deleted BOOLEAN NOT NULL DEFAULT FALSE");
    }

    private void ensureColumn(String table, String column, String alterSql) {
        String checkSql = """
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND column_name = ?
                """;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(checkSql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getInt(1) > 0) return;
            try (PreparedStatement alter = c.prepareStatement(alterSql)) {
                alter.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ══════════════════════════════════════════════════════════
    //  TRIP SEARCH
    // ══════════════════════════════════════════════════════════

    /**
     * Searches available trips by origin city (required),
     * destination city and date (optional).
     * Only returns scheduled trips with available seats.
     * Routes join through terminals to get city names.
     */
    public List<Map<String, Object>> searchTrips(String originCity, String destCity, String date) {
        StringBuilder sql = new StringBuilder("""
                SELECT t.id,
                       COALESCE(NULLIF(t_orig.name, ''), t_orig.city) AS origin,
                       COALESCE(NULLIF(t_dest.name, ''), t_dest.city) AS destination,
                       t.departure_time,
                       t.arrival_time,
                       COALESCE(bus.seat_count, t.total_seats) AS total_seats,
                       GREATEST(COALESCE(bus.seat_count, t.total_seats) - COALESCE(seat_counts.reserved_seats, 0), 0) AS available_seats,
                       t.price,
                       t.status
                FROM trips t
                LEFT JOIN buses bus ON bus.id = t.bus_id
                JOIN routes    r      ON t.route_id               = r.id
                JOIN terminals t_orig ON t.origin_terminal_id      = t_orig.id
                JOIN terminals t_dest ON t.destination_terminal_id = t_dest.id
                LEFT JOIN (
                    SELECT b.trip_id, COUNT(pd.id) AS reserved_seats
                    FROM bookings b
                    JOIN passengers_detail pd ON pd.booking_id = b.id
                    WHERE LOWER(COALESCE(b.payment_status, '')) <> 'cancelled'
                    GROUP BY b.trip_id
                ) seat_counts ON seat_counts.trip_id = t.id
                WHERE (
                    t_orig.city = ?
                    OR t_orig.name = ?
                    OR TRIM(CONCAT(t_orig.city, ' ', COALESCE(t_orig.name, ''))) = ?
                    OR COALESCE(NULLIF(t_orig.name, ''), t_orig.city) = ?
                )
                  AND t.status = 'scheduled'
                  AND GREATEST(COALESCE(bus.seat_count, t.total_seats) - COALESCE(seat_counts.reserved_seats, 0), 0) > 0
                  AND t.departure_time > NOW()
                """);

        List<Object> params = new ArrayList<>();
        params.add(originCity);
        params.add(originCity);
        params.add(originCity);
        params.add(originCity);

        if (destCity != null && !destCity.isBlank()) {
            sql.append("""
                    AND (
                        t_dest.city = ?
                        OR t_dest.name = ?
                        OR TRIM(CONCAT(t_dest.city, ' ', COALESCE(t_dest.name, ''))) = ?
                        OR COALESCE(NULLIF(t_dest.name, ''), t_dest.city) = ?
                    )
                    """);
            params.add(destCity.trim());
            params.add(destCity.trim());
            params.add(destCity.trim());
            params.add(destCity.trim());
        }

        if (date != null && !date.isBlank()) {
            // departure_time is a full datetime — filter by date portion
            sql.append(" AND DATE(t.departure_time) = ?");
            params.add(date.trim());
        }

        sql.append(" ORDER BY t.departure_time ASC");

        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
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
                row.put("price",          rs.getBigDecimal("price"));
                row.put("status",         rs.getString("status"));
                result.add(row);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return result;
    }

    /**
     * Returns current available seats for a trip.
     */
    public int getAvailableSeats(int tripId) {
        String sql = """
                SELECT GREATEST(COALESCE(bus.seat_count, t.total_seats) - COALESCE(seat_counts.reserved_seats, 0), 0) AS available_seats
                FROM trips t
                LEFT JOIN buses bus ON bus.id = t.bus_id
                LEFT JOIN (
                    SELECT b.trip_id, COUNT(pd.id) AS reserved_seats
                    FROM bookings b
                    JOIN passengers_detail pd ON pd.booking_id = b.id
                    WHERE LOWER(COALESCE(b.payment_status, '')) <> 'cancelled'
                    GROUP BY b.trip_id
                ) seat_counts ON seat_counts.trip_id = t.id
                WHERE t.id = ?
                """;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("available_seats");
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public List<Map<String, Object>> contactOperators() {
        ensureContactSchema();
        String sql = """
                SELECT u.id, u.full_name, u.email,
                       t.id AS terminal_id,
                       COALESCE(NULLIF(t.name, ''), t.city) AS terminal,
                       t.phone
                FROM users u
                JOIN terminals t ON t.id = u.terminal_id
                WHERE u.role = 'operator'
                  AND u.is_active = TRUE
                ORDER BY terminal, u.full_name
                """;
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getInt("id"));
                row.put("fullName", rs.getString("full_name"));
                row.put("email", rs.getString("email"));
                row.put("terminalId", rs.getInt("terminal_id"));
                row.put("terminal", rs.getString("terminal"));
                row.put("phone", rs.getString("phone"));
                rows.add(row);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return rows;
    }

    public String sendOperatorMessage(int passengerId, int operatorId, String subject, String message) {
        ensureContactSchema();
        if (subject == null || subject.isBlank()) return "Subject is required.";
        if (message == null || message.isBlank()) return "Message is required.";
        if (!operatorExists(operatorId)) return "Operator not found.";

        String sql = """
                INSERT INTO contact_messages (passenger_id, operator_id, subject, message)
                VALUES (?, ?, ?, ?)
                """;
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, passengerId);
            ps.setInt(2, operatorId);
            ps.setString(3, subject.trim());
            ps.setString(4, message.trim());
            if (ps.executeUpdate() == 1) {
                notifyUser(operatorId, "New passenger contact message: " + subject.trim());
                return null;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return "Failed to send message.";
    }

    private void notifyUser(int userId, String message) {
        String sql = "INSERT INTO notifications (user_id, trip_id, message) VALUES (?, NULL, ?)";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setString(2, message);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public List<Map<String, Object>> contactMessagesForPassenger(int passengerId) {
        ensureContactSchema();
        String sql = """
                SELECT cm.id, cm.subject, cm.message, cm.reply, cm.status, cm.created_at, cm.replied_at,
                       u.full_name AS operator_name,
                       COALESCE(NULLIF(t.name, ''), t.city) AS terminal
                FROM contact_messages cm
                JOIN users u ON u.id = cm.operator_id
                LEFT JOIN terminals t ON t.id = u.terminal_id
                WHERE cm.passenger_id = ?
                ORDER BY cm.created_at DESC
                """;
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, passengerId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) rows.add(mapContactMessage(rs));
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return rows;
    }

    private boolean operatorExists(int operatorId) {
        String sql = "SELECT 1 FROM users WHERE id = ? AND role = 'operator' AND is_active = TRUE LIMIT 1";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, operatorId);
            return ps.executeQuery().next();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    private Map<String, Object> mapContactMessage(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getInt("id"));
        row.put("subject", rs.getString("subject"));
        row.put("message", rs.getString("message"));
        row.put("reply", rs.getString("reply"));
        row.put("status", rs.getString("status"));
        row.put("createdAt", rs.getString("created_at"));
        row.put("repliedAt", rs.getString("replied_at"));
        row.put("operatorName", rs.getString("operator_name"));
        row.put("terminal", rs.getString("terminal"));
        return row;
    }

    // ══════════════════════════════════════════════════════════
    //  BOOKING CREATION
    // ══════════════════════════════════════════════════════════

    /**
     * Creates bookings for all passengers in a single atomic transaction.
     *
     * Schema:
     *   bookings(passenger_id, trip_id, payment_method, payment_status)
     *   passengers_detail(booking_id, first_name, last_name, phone, age,
     *                     seat_number, discount_pct, final_price, special_needs)
     *
     * payment_method in DB: 'online' (card) | 'terminal' (cash)
     * payment_status: 'paid' for online, 'pending' for terminal
     *
     * @return list of booking response maps, or null on failure.
     */
    public List<Map<String, Object>> createBookings(int tripId, int passengerId,
                                                    String paymentMethod,
                                                    List<Map<String, Object>> passengers) {

        // Map frontend values to DB enum values
        String dbPaymentMethod = "card".equals(paymentMethod) ? "online" : "terminal";
        String dbPaymentStatus = "online".equals(dbPaymentMethod) ? "paid" : "pending";

        String bookingSql = """
                INSERT INTO bookings (passenger_id, trip_id, payment_method, payment_status)
                VALUES (?, ?, ?, ?)
                """;

        String detailSql = """
                INSERT INTO passengers_detail
                    (booking_id, first_name, last_name, phone, age,
                     seat_number, discount_pct, final_price, special_needs)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        String takenSeatsSql = "SELECT seat_number FROM passengers_detail pd " +
                "JOIN bookings b ON pd.booking_id = b.id " +
                "WHERE b.trip_id = ? " +
                "AND LOWER(COALESCE(b.payment_status, '')) <> 'cancelled'";

        String tripSql = """
                SELECT t.price, COALESCE(bus.seat_count, t.total_seats) AS total_seats
                FROM trips t
                LEFT JOIN buses bus ON bus.id = t.bus_id
                WHERE t.id = ?
                """;

        String decrSql = "UPDATE trips SET available_seats = available_seats - 1 " +
                "WHERE id = ? AND available_seats > 0";

        List<Map<String, Object>> created = new ArrayList<>();

        try (Connection c = conn()) {
            c.setAutoCommit(false);
            try {
                // Fetch base price and total seats
                BigDecimal basePrice;
                int totalSeats;
                try (PreparedStatement ps = c.prepareStatement(tripSql)) {
                    ps.setInt(1, tripId);
                    ResultSet rs = ps.executeQuery();
                    if (!rs.next()) throw new SQLException("Trip not found: " + tripId);
                    basePrice  = rs.getBigDecimal("price");
                    totalSeats = rs.getInt("total_seats");
                }

                // Get already-taken seat numbers
                Set<Integer> takenSeats = new HashSet<>();
                try (PreparedStatement ps = c.prepareStatement(takenSeatsSql)) {
                    ps.setInt(1, tripId);
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) takenSeats.add(rs.getInt("seat_number"));
                }

                int nextSeat = 1;

                for (Map<String, Object> p : passengers) {
                    // Find next free seat
                    while (takenSeats.contains(nextSeat) && nextSeat <= totalSeats) nextSeat++;
                    if (nextSeat > totalSeats)
                        throw new SQLException("No more available seats on trip " + tripId);
                    takenSeats.add(nextSeat);

                    String firstName    = p.getOrDefault("firstName",    "").toString().trim();
                    String lastName     = p.getOrDefault("lastName",     "").toString().trim();
                    String phone        = p.getOrDefault("phone",        "").toString().trim();
                    int    age          = Integer.parseInt(p.get("age").toString());
                    boolean specialNeeds = Boolean.parseBoolean(p.getOrDefault("specialNeeds", "false").toString());

                    int        discountPct = calculateDiscount(age);
                    BigDecimal finalPrice  = applyDiscount(basePrice, discountPct);

                    // Insert into bookings
                    long bookingId;
                    try (PreparedStatement ps = c.prepareStatement(bookingSql, Statement.RETURN_GENERATED_KEYS)) {
                        ps.setInt(1, passengerId);
                        ps.setInt(2, tripId);
                        ps.setString(3, dbPaymentMethod);
                        ps.setString(4, dbPaymentStatus);
                        ps.executeUpdate();
                        ResultSet keys = ps.getGeneratedKeys();
                        if (!keys.next()) throw new SQLException("No booking ID returned.");
                        bookingId = keys.getLong(1);
                    }

                    // Insert into passengers_detail
                    try (PreparedStatement ps = c.prepareStatement(detailSql)) {
                        ps.setLong(1, bookingId);
                        ps.setString(2, firstName);
                        ps.setString(3, lastName);
                        ps.setString(4, phone.isEmpty() ? "—" : phone);
                        ps.setInt(5, age);
                        ps.setInt(6, nextSeat);
                        ps.setInt(7, discountPct);
                        ps.setBigDecimal(8, finalPrice);
                        ps.setBoolean(9, specialNeeds);
                        ps.executeUpdate();
                    }

                    // Decrement available_seats
                    try (PreparedStatement ps = c.prepareStatement(decrSql)) {
                        ps.setInt(1, tripId);
                        if (ps.executeUpdate() == 0)
                            throw new SQLException("Failed to decrement available_seats for trip " + tripId);
                    }

                    // Build response
                    Map<String, Object> resp = buildBookingResponse(
                            c, bookingId, tripId, firstName, lastName, age,
                            nextSeat, discountPct, finalPrice,
                            dbPaymentMethod, dbPaymentStatus
                    );
                    created.add(resp);
                    nextSeat++;
                }

                c.commit();
                return created;

            } catch (SQLException e) {
                c.rollback();
                e.printStackTrace();
                return null;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return null;
        }
    }

    private Map<String, Object> buildBookingResponse(Connection c, long bookingId, int tripId,
                                                     String firstName, String lastName, int age,
                                                     int seatNumber, int discountPct,
                                                     BigDecimal finalPrice,
                                                     String paymentMethod, String paymentStatus)
            throws SQLException {

        String sql = """
                SELECT t_orig.city AS origin, t_dest.city AS destination,
                       t.departure_time, t.arrival_time, t.price
                FROM trips t
                JOIN terminals t_orig ON t.origin_terminal_id      = t_orig.id
                JOIN terminals t_dest ON t.destination_terminal_id = t_dest.id
                WHERE t.id = ?
                """;

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("bookingId",     bookingId);
        row.put("tripId",        tripId);
        row.put("firstName",     firstName);
        row.put("lastName",      lastName);
        row.put("age",           age);
        row.put("seatNumber",    seatNumber);
        row.put("discountPct",   discountPct);
        row.put("finalPrice",    finalPrice);
        // Return frontend-friendly labels
        row.put("paymentMethod", "online".equals(paymentMethod) ? "card" : "terminal");
        row.put("paymentStatus", paymentStatus);

        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, tripId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                row.put("origin",        rs.getString("origin"));
                row.put("destination",   rs.getString("destination"));
                row.put("departureTime", rs.getString("departure_time"));
                row.put("arrivalTime",   rs.getString("arrival_time"));
                row.put("basePrice",     rs.getBigDecimal("price"));
            }
        }
        return row;
    }

    // ══════════════════════════════════════════════════════════
    //  MY BOOKINGS
    // ══════════════════════════════════════════════════════════

    /**
     * Returns all bookings for a passenger, enriched with trip/route/passenger detail info.
     * Ordered by departure_time descending.
     */
    public List<Map<String, Object>> getBookingsForPassenger(int passengerId) {
        String sql = """
                SELECT b.id             AS booking_id,
                       pd.first_name,
                       pd.last_name,
                       pd.age,
                       pd.seat_number,
                       pd.discount_pct,
                       pd.final_price,
                       pd.special_needs,
                       b.payment_method,
                       b.payment_status,
                       t_orig.city      AS origin,
                       t_dest.city      AS destination,
                       t.departure_time,
                       t.arrival_time,
                       t.status         AS trip_status,
                       t.price          AS base_price
                FROM bookings b
                JOIN passengers_detail pd ON pd.booking_id         = b.id
                JOIN trips t              ON b.trip_id             = t.id
                JOIN terminals t_orig     ON t.origin_terminal_id  = t_orig.id
                JOIN terminals t_dest     ON t.destination_terminal_id = t_dest.id
                WHERE b.passenger_id = ?
                ORDER BY t.departure_time DESC
                """;

        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, passengerId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) result.add(mapBookingRow(rs));
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return result;
    }

    /**
     * Returns a single booking for a passenger (ownership enforced).
     */
    public Map<String, Object> getBookingForPassenger(int bookingId, int passengerId) {
        String sql = """
                SELECT b.id             AS booking_id,
                       pd.first_name,
                       pd.last_name,
                       pd.age,
                       pd.seat_number,
                       pd.discount_pct,
                       pd.final_price,
                       pd.special_needs,
                       b.payment_method,
                       b.payment_status,
                       t_orig.city      AS origin,
                       t_dest.city      AS destination,
                       t.departure_time,
                       t.arrival_time,
                       t.status         AS trip_status,
                       t.price          AS base_price
                FROM bookings b
                JOIN passengers_detail pd ON pd.booking_id             = b.id
                JOIN trips t              ON b.trip_id                 = t.id
                JOIN terminals t_orig     ON t.origin_terminal_id      = t_orig.id
                JOIN terminals t_dest     ON t.destination_terminal_id = t_dest.id
                WHERE b.id = ? AND b.passenger_id = ?
                """;

        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, bookingId);
            ps.setInt(2, passengerId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return mapBookingRow(rs);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    private Map<String, Object> mapBookingRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("bookingId",     rs.getLong("booking_id"));
        row.put("firstName",     rs.getString("first_name"));
        row.put("lastName",      rs.getString("last_name"));
        row.put("age",           rs.getInt("age"));
        row.put("seatNumber",    rs.getInt("seat_number"));
        row.put("discountPct",   rs.getInt("discount_pct"));
        row.put("finalPrice",    rs.getBigDecimal("final_price"));
        row.put("specialNeeds",  rs.getBoolean("special_needs"));
        // Return frontend-friendly labels
        String pm = rs.getString("payment_method");
        row.put("paymentMethod", "online".equals(pm) ? "card" : "terminal");
        row.put("paymentStatus", rs.getString("payment_status"));
        row.put("origin",        rs.getString("origin"));
        row.put("destination",   rs.getString("destination"));
        row.put("departureTime", rs.getString("departure_time"));
        row.put("arrivalTime",   rs.getString("arrival_time"));
        row.put("tripStatus",    rs.getString("trip_status"));
        row.put("basePrice",     rs.getBigDecimal("base_price"));
        return row;
    }

    // ══════════════════════════════════════════════════════════
    //  DISCOUNT LOGIC  — must match frontend DISCOUNT_TIERS
    // ══════════════════════════════════════════════════════════

    /**
     * Under 6  → 100% (free)
     * 6–11     → 50%  (child)
     * 12–25    → 20%  (student)
     * 65+      → 30%  (senior)
     * All others → 0%
     */
    public int calculateDiscount(int age) {
        if (age < 6)               return 100;
        if (age >= 6  && age <= 11) return 50;
        if (age >= 12 && age <= 25) return 20;
        if (age >= 65)              return 30;
        return 0;
    }

    private BigDecimal applyDiscount(BigDecimal basePrice, int discountPct) {
        if (discountPct >= 100) return BigDecimal.ZERO;
        BigDecimal multiplier = BigDecimal.valueOf(100 - discountPct)
                .divide(BigDecimal.valueOf(100));
        return basePrice.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
    }
}
