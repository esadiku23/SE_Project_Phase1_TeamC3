package al.albus.api;

import al.albus.service.AdminService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService svc = new AdminService();

    @PutMapping("/trips/{id}/status")
    public ResponseEntity<?> updateTripStatus(@PathVariable int id,
                                              @RequestBody Map<String, String> body,
                                              HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        String target = body.get("status");
        boolean updated = svc.transitionTripStatus(id, target);
        return updated
                ? ok(Map.of("message", "Trip status updated and notifications sent when required."))
                : bad("Invalid status change. Admins can only cancel scheduled or departed trips.");
    }

    // ─────────────────────────────────────────────
    //  Dashboard statistics
    // ─────────────────────────────────────────────

    @GetMapping("/stats")
    public ResponseEntity<?> stats(HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        return ok(svc.stats());
    }

    // ─────────────────────────────────────────────
    //  Terminals
    // ─────────────────────────────────────────────

    @GetMapping("/terminals")
    public ResponseEntity<?> terminals(HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        return ok(svc.terminals());
    }

    @GetMapping("/terminals/overview")
    public ResponseEntity<?> terminalOverview(HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        return ok(svc.terminalOverview());
    }

    // ─────────────────────────────────────────────
    //  Staff accounts  (operator + driver only)
    // ─────────────────────────────────────────────

    /**
     * GET /api/admin/users?role=all|operator|driver
     * Passengers are never returned.
     */
    @GetMapping("/users")
    public ResponseEntity<?> users(@RequestParam(defaultValue = "all") String role,
                                   HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        return ok(svc.staff(role));
    }

    @PostMapping("/operators")
    public ResponseEntity<?> addOperator(@RequestBody Map<String, String> body,
                                         HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        String name       = body.get("fullName");
        String email      = body.get("email");
        String password   = body.get("password");
        String terminalId = body.get("terminalId");

        String error = validateStaffInput(name, email, password, terminalId);
        if (error != null) return bad(error);

        error = svc.validateStaffCreate(name.trim(), email.trim(), password,
                Integer.parseInt(terminalId));
        if (error != null) return bad(error);

        boolean created = svc.addOperator(name.trim(), email.trim(), password,
                Integer.parseInt(terminalId));
        return created
                ? ok(Map.of("message", "Operator created successfully."))
                : bad("Failed to create operator.");
    }

    @PostMapping("/drivers")
    public ResponseEntity<?> addDriver(@RequestBody Map<String, String> body,
                                       HttpSession session) {
        if (!isAdmin(session)) return forbidden();

        String name       = body.get("fullName");
        String email      = body.get("email");
        String password   = body.get("password");
        String terminalId = body.get("terminalId");

        String error = validateStaffInput(name, email, password, terminalId);
        if (error != null) return bad(error);

        error = svc.validateStaffCreate(name.trim(), email.trim(), password,
                Integer.parseInt(terminalId));
        if (error != null) return bad(error);

        boolean created = svc.addDriver(name.trim(), email.trim(), password,
                Integer.parseInt(terminalId));
        return created
                ? ok(Map.of("message", "Driver created successfully."))
                : bad("Failed to create driver.");
    }

    /**
     * PUT /api/admin/users/{id}/deactivate
     * Sets is_active = FALSE.  Admin accounts and passengers cannot be deactivated.
     */
    @PutMapping("/users/{id}/deactivate")
    public ResponseEntity<?> deactivateUser(@PathVariable int id, HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        String error = svc.deactivateUser(id);
        return error == null
                ? ok(Map.of("message", "Account deactivated successfully."))
                : bad(error);
    }

    // ─────────────────────────────────────────────
    //  Terminal capacity
    // ─────────────────────────────────────────────

    @GetMapping("/terminal-capacity")
    public ResponseEntity<?> terminalCapacity(HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        return ok(svc.terminalCapacity());
    }

    @PutMapping("/terminal-capacity/{terminalId}")
    public ResponseEntity<?> updateTerminalCapacity(@PathVariable int terminalId,
                                                    @RequestBody Map<String, Object> body,
                                                    HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        try {
            Object raw = body.get("maxBusesDay");
            if (raw == null) raw = body.get("max_buses_day");
            if (raw == null) return bad("maxBusesDay is required.");
            int maxBusesDay = Integer.parseInt(raw.toString());
            String error = svc.updateTerminalCapacity(terminalId, maxBusesDay);
            return error == null
                    ? ok(Map.of("message", "Terminal capacity updated."))
                    : bad(error);
        } catch (NumberFormatException e) {
            return bad("Capacity must be a valid number.");
        }
    }

    // ─────────────────────────────────────────────
    //  Notifications / Messages
    // ─────────────────────────────────────────────

    @GetMapping("/notifications")
    public ResponseEntity<?> notifications(HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        return ok(svc.notifications());
    }

    @PostMapping("/users/{id}/notifications")
    public ResponseEntity<?> sendNotification(@PathVariable int id,
                                              @RequestBody Map<String, String> body,
                                              HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        String error = svc.sendNotification(id, body.get("message"));
        return error == null
                ? ok(Map.of("message", "Message sent successfully."))
                : bad(error);
    }

    // ─────────────────────────────────────────────
    //  Drivers  (for trip form dropdown)
    // ─────────────────────────────────────────────

    @GetMapping("/drivers")
    public ResponseEntity<?> drivers(@RequestParam int terminalId, HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        return ok(svc.driversForTerminal(terminalId));
    }

    @GetMapping("/buses")
    public ResponseEntity<?> buses(@RequestParam(required = false) Integer terminalId,
                                   HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        return ok(terminalId == null ? svc.buses() : svc.busesForTerminal(terminalId));
    }

    @PostMapping("/buses")
    public ResponseEntity<?> addBus(@RequestBody Map<String, Object> body,
                                    HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        try {
            BusInput input = parseBus(body);
            int id = svc.addBus(input.terminalId, input.plateNumber, input.model, input.seatCount, input.status);
            if (id == -2) return bad("Terminal does not exist.");
            if (id == -3) return bad("Seat count must be positive.");
            return id == -1 ? bad("Failed to create bus. Plate number may already exist.")
                    : ok(Map.of("id", id, "message", "Bus created."));
        } catch (Exception e) {
            return bad("Invalid bus input: " + e.getMessage());
        }
    }

    @PutMapping("/buses/{id}")
    public ResponseEntity<?> updateBus(@PathVariable int id,
                                       @RequestBody Map<String, Object> body,
                                       HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        return bad("Admins can only create or deactivate buses. Operators manage bus status.");
    }

    @PutMapping("/buses/{id}/deactivate")
    public ResponseEntity<?> deactivateBus(@PathVariable int id, HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        return svc.deactivateBus(id)
                ? ok(Map.of("message", "Bus deactivated."))
                : bad("Failed to deactivate bus.");
    }

    // ─────────────────────────────────────────────
    //  Routes
    // ─────────────────────────────────────────────

    @GetMapping("/routes")
    public ResponseEntity<?> routes(HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        return ok(svc.routes());
    }

    @PostMapping("/routes")
    public ResponseEntity<?> addRoute(@RequestBody Map<String, Object> body,
                                      HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        try {
            int    originTerminalId = Integer.parseInt(body.get("originTerminalId").toString());
            String destination      = body.get("destination").toString().trim();
            int    distanceKm       = Integer.parseInt(body.get("distanceKm").toString());

            if (destination.isBlank()) return bad("Destination is required.");
            if (distanceKm <= 0)       return bad("Distance must be a positive number.");

            int id = svc.addRoute(originTerminalId, destination, distanceKm);
            return id == -1
                    ? bad("Could not create route. Verify that the terminal exists.")
                    : ok(Map.of("id", id, "message", "Route created."));
        } catch (Exception e) {
            return bad("Invalid route input: " + e.getMessage());
        }
    }

    @PostMapping("/routes/cleanup-requested")
    public ResponseEntity<?> cleanupRequestedRoutes(HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        int removed = svc.cleanupRequestedRoutes();
        return ok(Map.of("removed", removed, "message", "Requested-only routes without trips removed."));
    }

    @PutMapping("/routes/{id}")
    public ResponseEntity<?> updateRoute(@PathVariable int id,
                                         @RequestBody Map<String, Object> body,
                                         HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        try {
            String destination = body.get("destination").toString().trim();
            int    distanceKm  = Integer.parseInt(body.get("distanceKm").toString());

            if (destination.isBlank()) return bad("Destination is required.");
            if (distanceKm <= 0)       return bad("Distance must be a positive number.");

            boolean updated = svc.updateRoute(id, destination, distanceKm);
            return updated ? ok(Map.of("message", "Route updated.")) : bad("Update failed.");
        } catch (Exception e) {
            return bad("Invalid route input: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    //  Trips  (structural data only — admin does NOT change trip status)
    // ─────────────────────────────────────────────

    @GetMapping("/trips")
    public ResponseEntity<?> trips(HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        return ok(svc.trips());
    }

    @PostMapping("/trips")
    public ResponseEntity<?> addTrip(@RequestBody Map<String, Object> body,
                                     HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        try {
            TripInput input = parseTrip(body, 0);
            int id = svc.addTrip(input.routeId, input.busId, input.driverId, input.originTerminalId,
                    input.date, input.departure, input.arrival,
                    input.price);
            if (id == -3) return bad("Terminal daily bus capacity has been reached for that date.");
            if (id == -4) return bad("Selected bus is not available at this terminal.");
            return id == -1
                    ? bad("Failed to create trip.")
                    : ok(Map.of("id", id, "message", "Trip created and notifications sent."));
        } catch (Exception e) {
            return bad("Invalid trip input: " + e.getMessage());
        }
    }

    @PutMapping("/trips/{id}")
    public ResponseEntity<?> updateTrip(@PathVariable int id,
                                        @RequestBody Map<String, Object> body,
                                        HttpSession session) {
        if (!isAdmin(session)) return forbidden();
        try {
            TripInput input = parseTrip(body, id);
            boolean updated = svc.updateTrip(id, input.routeId, input.busId, input.driverId,
                    input.originTerminalId, input.date,
                    input.departure, input.arrival,
                    input.price);
            return updated
                    ? ok(Map.of("message", "Trip updated and notifications sent."))
                    : bad("Update failed. The terminal may have reached its daily capacity.");
        } catch (Exception e) {
            return bad("Invalid trip input: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    //  Private helpers
    // ─────────────────────────────────────────────

    private TripInput parseTrip(Map<String, Object> body, int id) {
        String depStr = body.get("departureTime").toString();
        String arrStr = body.get("arrivalTime").toString();
        return new TripInput(
                id,
                Integer.parseInt(body.get("routeId").toString()),
                Integer.parseInt(body.get("busId").toString()),
                Integer.parseInt(body.get("driverId").toString()),
                Integer.parseInt(body.get("originTerminalId").toString()),
                LocalDate.parse(depStr.substring(0, 10)),
                depStr.length() >= 16 ? depStr.substring(0, 16).replace("T", " ") : depStr,
                arrStr.length() >= 16 ? arrStr.substring(0, 16).replace("T", " ") : arrStr,
                new BigDecimal(body.get("price").toString())
        );
    }

    private BusInput parseBus(Map<String, Object> body) {
        return new BusInput(
                Integer.parseInt(body.get("terminalId").toString()),
                body.get("plateNumber").toString().trim(),
                body.get("model").toString().trim(),
                Integer.parseInt(body.get("seatCount").toString()),
                body.getOrDefault("status", "available").toString()
        );
    }

    private String validateStaffInput(String name, String email,
                                      String password, String terminalId) {
        if (name       == null || name.isBlank())       return "Full name is required.";
        if (email      == null || email.isBlank())      return "Email is required.";
        if (password   == null || password.length() < 6) return "Password must be at least 6 characters.";
        if (terminalId == null || terminalId.isBlank()) return "Terminal is required.";
        try {
            if (Integer.parseInt(terminalId) <= 0) return "Please select a valid terminal.";
        } catch (NumberFormatException e) {
            return "Invalid terminal selection.";
        }
        return null;
    }

    private boolean isAdmin(HttpSession session) {
        return "admin".equals(session.getAttribute("userRole"));
    }

    private ResponseEntity<?> ok(Object data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    private ResponseEntity<?> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "message", msg));
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(403).body(Map.of("success", false, "message", "Forbidden"));
    }

    private record TripInput(int id, int routeId, int busId, int driverId, int originTerminalId,
                             LocalDate date, String departure, String arrival,
                             BigDecimal price) {}
    private record BusInput(int terminalId, String plateNumber, String model, int seatCount, String status) {}
}
