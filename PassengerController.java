package al.albus.api;

import al.albus.service.PassengerService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/passenger")
public class PassengerController {

    private final PassengerService svc = new PassengerService();

    // ── Auth guard ─────────────────────────────────────────────

    private Integer passengerId(HttpSession session) {
        Object role = session.getAttribute("userRole");
        if (!"passenger".equals(role)) return null;
        Object id = session.getAttribute("userId");
        if (id instanceof Number) return ((Number) id).intValue();
        try {
            return Integer.parseInt(String.valueOf(id));
        } catch (Exception e) {
            return null;
        }
    }

    // ══ TRIP SEARCH ═══════════════════════════════════════════

    /**
     * GET /api/passenger/trips/search
     *
     * Query params:
     *   origin      — required, city name (e.g. "Tiranë")
     *   destination — optional, city name
     *   date        — optional, yyyy-MM-dd
     */
    @GetMapping("/trips/search")
    public ResponseEntity<?> searchTrips(
            @RequestParam String origin,
            @RequestParam(required = false) String destination,
            @RequestParam(required = false) String date,
            HttpSession session) {

        if (passengerId(session) == null) return forbidden();
        if (origin == null || origin.isBlank()) return bad("Origin city is required.");
        return ok(svc.searchTrips(origin.trim(), destination, date));
    }

    // ══ BOOKING ════════════════════════════════════════════════

    /**
     * POST /api/passenger/bookings
     *
     * Body:
     * {
     *   "tripId": 1,
     *   "paymentMethod": "card" | "terminal",
     *   "passengers": [
     *     { "firstName": "Arjol", "lastName": "Basha", "phone": "069...", "age": 28, "specialNeeds": false }
     *   ]
     * }
     */
    @PostMapping("/bookings")
    public ResponseEntity<?> createBooking(@RequestBody Map<String, Object> body,
                                           HttpSession session) {
        Integer pid = passengerId(session);
        if (pid == null) return forbidden();

        Object tripIdRaw = body.get("tripId");
        if (tripIdRaw == null) return bad("tripId is required.");
        int tripId;
        try { tripId = Integer.parseInt(tripIdRaw.toString()); }
        catch (NumberFormatException e) { return bad("Invalid tripId."); }

        String paymentMethod = body.getOrDefault("paymentMethod", "terminal").toString().toLowerCase();
        if (!paymentMethod.equals("card") && !paymentMethod.equals("terminal"))
            return bad("paymentMethod must be 'card' or 'terminal'.");

        Object passRaw = body.get("passengers");
        if (!(passRaw instanceof List<?> passList) || passList.isEmpty())
            return bad("At least one passenger is required.");

        int available = svc.getAvailableSeats(tripId);
        if (available < passList.size())
            return bad("Not enough seats. Only " + available + " seat(s) available.");

        List<Map<String, Object>> passengers;
        try {
            //noinspection unchecked
            passengers = (List<Map<String, Object>>) passList;
        } catch (ClassCastException e) {
            return bad("Invalid passengers format.");
        }

        // Validate each passenger
        for (int i = 0; i < passengers.size(); i++) {
            Map<String, Object> p = passengers.get(i);
            String firstName = p.getOrDefault("firstName", "").toString().trim();
            String lastName  = p.getOrDefault("lastName",  "").toString().trim();
            Object ageRaw    = p.get("age");
            if (firstName.isEmpty()) return bad("Passenger " + (i+1) + ": firstName is required.");
            if (lastName.isEmpty())  return bad("Passenger " + (i+1) + ": lastName is required.");
            if (ageRaw == null)      return bad("Passenger " + (i+1) + ": age is required.");
            try {
                int age = Integer.parseInt(ageRaw.toString());
                if (age < 0 || age > 120) return bad("Passenger " + (i+1) + ": age must be 0–120.");
                if (age < 6) return bad("Passenger " + (i+1) + ": passengers under 6 must be booked through the operator. Please contact the operator for assistance.");
            } catch (NumberFormatException e) {
                return bad("Passenger " + (i+1) + ": invalid age.");
            }
        }

        List<Map<String, Object>> created = svc.createBookings(tripId, pid, paymentMethod, passengers);
        if (created == null || created.isEmpty())
            return bad("Booking failed. Seats may no longer be available.");

        return ok(created);
    }

    // ══ MY BOOKINGS ════════════════════════════════════════════

    /**
     * GET /api/passenger/bookings
     * All bookings for the logged-in passenger.
     */
    @GetMapping("/bookings")
    public ResponseEntity<?> getMyBookings(HttpSession session) {
        Integer pid = passengerId(session);
        if (pid == null) return forbidden();
        return ok(svc.getBookingsForPassenger(pid));
    }

    /**
     * GET /api/passenger/bookings/{id}
     * Single booking (ownership enforced).
     */
    @GetMapping("/bookings/{id}")
    public ResponseEntity<?> getBooking(@PathVariable int id, HttpSession session) {
        Integer pid = passengerId(session);
        if (pid == null) return forbidden();
        Map<String, Object> booking = svc.getBookingForPassenger(id, pid);
        if (booking == null) return bad("Booking not found.");
        return ok(booking);
    }

    @GetMapping("/contact/operators")
    public ResponseEntity<?> contactOperators(HttpSession session) {
        if (passengerId(session) == null) return forbidden();
        return ok(svc.contactOperators());
    }

    @GetMapping("/contact/messages")
    public ResponseEntity<?> contactMessages(HttpSession session) {
        Integer pid = passengerId(session);
        if (pid == null) return forbidden();
        return ok(svc.contactMessagesForPassenger(pid));
    }

    @PostMapping("/contact/messages")
    public ResponseEntity<?> sendContactMessage(@RequestBody Map<String, Object> body,
                                                HttpSession session) {
        Integer pid = passengerId(session);
        if (pid == null) return forbidden();
        try {
            int operatorId = Integer.parseInt(body.get("operatorId").toString());
            String subject = body.getOrDefault("subject", "").toString();
            String message = body.getOrDefault("message", "").toString();
            String error = svc.sendOperatorMessage(pid, operatorId, subject, message);
            return error == null ? ok(Map.of("message", "Message sent.")) : bad(error);
        } catch (Exception e) {
            return bad("Invalid contact message.");
        }
    }

    // ── Helpers ───────────────────────────────────────────────
    private ResponseEntity<?> ok(Object data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }
    private ResponseEntity<?> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "message", msg));
    }
    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(403).body(Map.of("success", false, "message", "Forbidden"));
    }
}
