package al.albus.api;

import al.albus.service.DriverService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/driver")
public class DriverController {

    private final DriverService svc = new DriverService();

    // ── Auth guard ─────────────────────────────────────────────────────────────
    //
    //  BUG FIX: The original code did:
    //      return id instanceof Integer ? (Integer) id : null;
    //
    //  JDBC getGeneratedKeys() / ResultSet.getObject() often returns Long, not
    //  Integer, even for INT columns. If the auth controller stored userId as a
    //  Long, this cast silently returned null → every driver request got a 403
    //  Forbidden, which the frontend showed as "Failed to update status."
    //
    //  Fix: accept Number (covers Integer, Long, Short) and convert safely.
    // ──────────────────────────────────────────────────────────────────────────

    private Integer driverId(HttpSession session) {
        Object role = session.getAttribute("userRole");
        if (!"driver".equals(role)) return null;

        Object id = session.getAttribute("userId");
        if (id instanceof Number) return ((Number) id).intValue();

        try {
            return Integer.parseInt(String.valueOf(id));
        } catch (Exception e) {
            return null;
        }
    }

    @GetMapping("/terminal")
    public ResponseEntity<?> terminal(HttpSession session) {
        Integer did = driverId(session);
        if (did == null) return forbidden();
        return ok(svc.getTerminalInfoForDriver(did));
    }

    @PutMapping("/trips/{id}/depart")
    public ResponseEntity<?> markDeparted(@PathVariable int id, HttpSession session) {
        Integer did = driverId(session);
        if (did == null) return forbidden();

        if (!svc.tripBelongsToDriver(id, did))
            return bad("Trip not found or not assigned to you.");

        boolean ok = svc.transitionStatus(id, "scheduled", "departed");

        if (!ok)
            return bad("Cannot mark as departed. Trip must be scheduled and its departure time must have arrived.");

        return ok(Map.of("message", "Trip marked as departed."));
    }

    // ══ TRIPS ═══════════════════════════════════════════════════════════════

    /**
     * GET /api/driver/trips
     * Returns all trips assigned to the logged-in driver.
     */
    @GetMapping("/trips")
    public ResponseEntity<?> getMyTrips(HttpSession session) {
        Integer did = driverId(session);
        if (did == null) return forbidden();
        return ok(svc.getTripsForDriver(did));
    }

    // ══ STATUS TRANSITIONS ══════════════════════════════════════════════════

    /**
     * PUT /api/driver/trips/{id}/depart
     *
     * Rules:
     * - Trip must belong to this driver.
     * - Trip status must be 'scheduled'.
     * - Passenger count does NOT affect departure — a bus can leave with 0 passengers.
     *   Low-passenger notifications are handled separately by admin/operator, not here.
     */

    /**
     * PUT /api/driver/trips/{id}/arrive
     *
     * Rules:
     * - Trip must belong to this driver.
     * - Trip status must be 'departed'.
     */
    @PutMapping("/trips/{id}/arrive")
    public ResponseEntity<?> markArrived(@PathVariable int id, HttpSession session) {
        Integer did = driverId(session);
        if (did == null) return forbidden();

        if (!svc.tripBelongsToDriver(id, did))
            return bad("Trip not found or not assigned to you.");

        boolean ok = svc.transitionStatus(id, "departed", "arrived");
        if (!ok)
            return bad("Cannot mark as arrived. Trip must be departed and its arrival time must have arrived.");

        return ok(Map.of("message", "Trip marked as arrived."));
    }

    // ══ MANIFEST ════════════════════════════════════════════════════════════

    /**
     * GET /api/driver/trips/{id}/manifest
     */
    @GetMapping("/trips/{id}/manifest")
    public ResponseEntity<?> getManifest(@PathVariable int id, HttpSession session) {
        Integer did = driverId(session);
        if (did == null) return forbidden();
        if (!svc.tripBelongsToDriver(id, did))
            return bad("Trip not found or not assigned to you.");
        return ok(svc.getPassengerManifest(id));
    }

    // ══ CASH PAYMENTS ═══════════════════════════════════════════════════════

    /**
     * GET /api/driver/trips/{id}/cash-passengers
     */
    @GetMapping("/trips/{id}/cash-passengers")
    public ResponseEntity<?> getCashPassengers(@PathVariable int id, HttpSession session) {
        Integer did = driverId(session);
        if (did == null) return forbidden();
        if (!svc.tripBelongsToDriver(id, did))
            return bad("Trip not found or not assigned to you.");
        return ok(svc.getCashPassengers(id));
    }

    /**
     * PUT /api/driver/bookings/{bookingId}/mark-paid
     */
    @PutMapping("/bookings/{bookingId}/mark-paid")
    public ResponseEntity<?> markPaid(@PathVariable int bookingId, HttpSession session) {
        Integer did = driverId(session);
        if (did == null) return forbidden();
        if (!svc.bookingBelongsToDriver(bookingId, did))
            return bad("Booking not found or not on your trip.");
        boolean ok = svc.markBookingPaid(bookingId);
        if (!ok) return bad("Failed to mark as paid. It may already be paid.");
        return ok(Map.of("message", "Payment collected."));
    }

    @PutMapping("/bookings/{bookingId}/remove-pending")
    public ResponseEntity<?> removePending(@PathVariable int bookingId, HttpSession session) {
        Integer did = driverId(session);
        if (did == null) return forbidden();
        if (!svc.bookingBelongsToDriver(bookingId, did))
            return bad("Booking not found or not on your trip.");
        boolean ok = svc.removePendingBooking(bookingId);
        if (!ok) return bad("Failed to remove passenger. Only pending passengers can be removed.");
        return ok(Map.of("message", "Passenger removed from trip."));
    }

    // ── Response helpers ─────────────────────────────────────────────────────
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
