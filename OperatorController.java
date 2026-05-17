package al.albus.api;

import al.albus.model.*;
import al.albus.service.OperatorService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/operator")
public class OperatorController {

    private final OperatorService svc = new OperatorService();

    // ── Auth guard helper ─────────────────────────────────────
    private Integer terminalId(HttpSession session) {
        Object role = session.getAttribute("userRole");
        if (!"operator".equals(role)) return null;
        Object tid = session.getAttribute("terminalId");
        if (tid instanceof Number) return ((Number) tid).intValue();
        try {
            return Integer.parseInt(String.valueOf(tid));
        } catch (Exception e) {
            return null;
        }
    }

    private Integer userId(HttpSession session) {
        Object id = session.getAttribute("userId");
        if (id instanceof Number) return ((Number) id).intValue();
        try {
            return Integer.parseInt(String.valueOf(id));
        } catch (Exception e) {
            return null;
        }
    }

    // ══ TRIPS ═════════════════════════════════════════════════

    @GetMapping("/departures")
    public ResponseEntity<?> getDepartures(HttpSession session) {
        Integer tid = terminalId(session);
        if (tid == null) return forbidden();
        List<Trip> trips = svc.getTripsForTerminal(tid);
        return ok(trips);
    }

    @GetMapping("/arrivals")
    public ResponseEntity<?> getArrivals(HttpSession session) {
        Integer tid = terminalId(session);
        if (tid == null) return forbidden();
        return ok(svc.getArrivingTrips(tid));
    }

    @GetMapping("/terminal")
    public ResponseEntity<?> terminal(HttpSession session) {
        Integer tid = terminalId(session);
        if (tid == null) return forbidden();
        return ok(svc.getTerminalInfo(tid));
    }

    @PostMapping("/trips")
    public ResponseEntity<?> createTrip(@RequestBody Map<String, Object> body,
                                        HttpSession session) {
        Integer tid = terminalId(session);
        if (tid == null) return forbidden();
        return bad("Operators must send trip requests for approval. Trips are not scheduled directly from this screen.");
    }

    @PutMapping("/trips/{id}")
    public ResponseEntity<?> updateTrip(@PathVariable int id,
                                        @RequestBody Map<String, Object> body,
                                        HttpSession session) {
        Integer tid = terminalId(session);
        if (tid == null) return forbidden();
        if (!svc.tripStartsFromTerminal(id, tid)) {
            return forbidden();
        }
        if (!svc.tripCanBeEdited(id)) {
            return bad("Arrived and cancelled trips cannot be edited.");
        }

        try {
            int routeId = svc.getTripRouteId(id);
            if (routeId <= 0) return bad("Trip route not found.");

            Trip t = new Trip();
            t.setId(id);
            t.setRouteId(routeId);
            t.setBusId(Integer.parseInt(body.get("busId").toString()));
            t.setDriverId(Integer.parseInt(body.get("driverId").toString()));
            t.setTerminalId(tid);
            String depStr = body.get("departureTime").toString();
            String arrStr = body.get("arrivalTime").toString();
            t.setDepartureTime(depStr.length() >= 16 ? depStr.substring(0,16).replace("T", " ") : depStr);
            t.setArrivalTime(arrStr.length() >= 16 ? arrStr.substring(0,16).replace("T", " ") : arrStr);
            t.setPrice(new BigDecimal(body.get("price").toString()));
            t.setTripDate(java.time.LocalDate.parse(depStr.substring(0,10)));
            boolean ok = svc.editTrip(t);
            return ok ? ok(Map.of("message","Updated.")) : bad("Update failed.");
        } catch (Exception e) { return bad(e.getMessage()); }
    }

    @PutMapping("/trips/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable int id,
                                          @RequestBody Map<String, String> body,
                                          HttpSession session) {
        Integer tid = terminalId(session);
        if (tid == null) return forbidden();

        String target = body.get("status");
        // Get current status first
        List<Trip> trips = svc.getTripsForTerminal(tid);
        Trip trip = trips.stream().filter(t -> t.getId() == id).findFirst().orElse(null);
        if (trip == null) return bad("Trip not found or not yours.");

        boolean ok = svc.transitionTripStatus(id, trip.getStatus(), target);
        if (!ok) return bad("Invalid status transition from '" + trip.getStatus() + "' to '" + target + "', or the scheduled time has not arrived yet.");
        return ok(Map.of("message", "Status updated."));
    }

    @GetMapping("/trips/{id}/seats")
    public ResponseEntity<?> getSeatMap(@PathVariable int id, HttpSession session) {
        Integer tid = terminalId(session);
        if (tid == null) return forbidden();
        if (!svc.tripStartsFromTerminal(id, tid)) return forbidden();
        return ok(svc.getTakenSeats(id));
    }

    @GetMapping("/trips/{id}/manifest")
    public ResponseEntity<?> getManifest(@PathVariable int id, HttpSession session) {
        Integer tid = terminalId(session);
        if (tid == null) return forbidden();
        if (!svc.tripStartsFromTerminal(id, tid)) return forbidden();
        return ok(svc.getPassengerManifest(id));
    }

    @GetMapping("/trips/{id}/bookings")
    public ResponseEntity<?> getBookings(@PathVariable int id, HttpSession session) {
        Integer tid = terminalId(session);
        if (tid == null) return forbidden();
        if (!svc.tripStartsFromTerminal(id, tid)) return forbidden();
        List<String[]> manifest = svc.getPassengerManifest(id);
        // Map to structured objects
        List<Map<String,Object>> result = manifest.stream().map(row -> Map.<String,Object>of(
                "firstName",     row[0].split(" ")[0],
                "lastName",      row[0].contains(" ") ? row[0].substring(row[0].indexOf(" ")+1) : "",
                "seatNumber",    row[1],
                "age",           row[2],
                "discountPct",   row[3].replace("%",""),
                "finalPrice",    row[4].replace(" L",""),
                "specialNeeds",  row[5],
                "paymentMethod", row[6],
                "paymentStatus", row[7]
        )).toList();
        return ok(result);
    }

    @GetMapping("/trips/{id}/overflow")
    public ResponseEntity<?> overflowCheck(@PathVariable int id, HttpSession session) {
        Integer tid = terminalId(session);
        if (tid == null) return forbidden();
        if (!svc.tripStartsFromTerminal(id, tid)) return forbidden();
        return ok(svc.evaluateOverflowTrip(id, tid));
    }

    @PostMapping("/trips/{id}/overflow/dispatch")
    public ResponseEntity<?> dispatchOverflowTrip(@PathVariable int id, HttpSession session) {
        Integer tid = terminalId(session);
        if (tid == null) return forbidden();
        if (!svc.tripStartsFromTerminal(id, tid)) return forbidden();
        int newTripId = svc.dispatchOverflowTrip(id, tid);
        return newTripId == -1
                ? bad("Extra trip cannot be dispatched. Check destination capacity and driver availability.")
                : ok(Map.of("id", newTripId));
    }

    // ══ ROUTES ════════════════════════════════════════════════

    @PostMapping("/trips/{id}/overflow/request")
    public ResponseEntity<?> requestOverflowTrip(@PathVariable int id, HttpSession session) {
        Integer tid = terminalId(session);
        Integer uid = userId(session);
        if (tid == null || uid == null) return forbidden();
        if (!svc.tripStartsFromTerminal(id, tid)) return forbidden();
        String error = svc.requestOverflowTrip(id, tid, uid);
        return error == null ? ok(Map.of("message", "Route request sent.")) : bad(error);
    }

    @GetMapping("/trip-templates")
    public ResponseEntity<?> getTripTemplates(HttpSession session) {
        Integer tid = terminalId(session);
        Integer uid = userId(session);
        if (tid == null || uid == null) return forbidden();
        return ok(svc.getTripTemplates(tid, uid));
    }

    @PostMapping("/trip-templates")
    public ResponseEntity<?> createTripTemplate(@RequestBody Map<String, Object> body,
                                                HttpSession session) {
        Integer tid = terminalId(session);
        Integer uid = userId(session);
        if (tid == null || uid == null) return forbidden();
        try {
            int id = svc.createTripTemplate(
                    tid,
                    uid,
                    Integer.parseInt(body.get("routeId").toString()),
                    Integer.parseInt(body.get("driverId").toString()),
                    Integer.parseInt(body.get("busId").toString()),
                    String.valueOf(body.get("departureTime")),
                    String.valueOf(body.get("arrivalTime")),
                    String.valueOf(body.get("daysOfWeek")),
                    new BigDecimal(body.get("price").toString())
            );
            return id > 0 ? ok(Map.of("id", id)) : bad("Failed to save weekly trip template.");
        } catch (Exception e) {
            return bad(e.getMessage());
        }
    }

    @DeleteMapping("/trip-templates/{id}")
    public ResponseEntity<?> deleteTripTemplate(@PathVariable int id, HttpSession session) {
        Integer tid = terminalId(session);
        Integer uid = userId(session);
        if (tid == null || uid == null) return forbidden();
        return svc.deleteTripTemplate(id, tid, uid)
                ? ok(Map.of("message", "Weekly trip removed."))
                : bad("Weekly trip not found.");
    }

    @PostMapping("/trip-templates/{id}/generate")
    public ResponseEntity<?> generateTripTemplate(@PathVariable int id,
                                                  @RequestBody(required = false) Map<String, String> body,
                                                  HttpSession session) {
        Integer tid = terminalId(session);
        Integer uid = userId(session);
        if (tid == null || uid == null) return forbidden();
        String weekStart = body == null ? null : body.get("weekStart");
        Map<String, Object> result = svc.generateTripTemplateWeek(id, tid, uid, weekStart);
        return Boolean.TRUE.equals(result.get("success")) ? ok(result) : bad(String.valueOf(result.get("message")));
    }

    @GetMapping("/routes")
    public ResponseEntity<?> getRoutes(HttpSession session) {
        Integer tid = terminalId(session);
        if (tid == null) return forbidden();
        return ok(svc.getRoutesForTerminal(tid));
    }

    @PostMapping("/routes")
    public ResponseEntity<?> createRoute(@RequestBody Map<String, Object> body,
                                         HttpSession session) {
        Integer tid = terminalId(session);
        if (tid == null) return forbidden();
        return bad("Operators cannot create route records. Ask an admin to define the route, then request approval for that existing route.");
    }

    @PutMapping("/routes/{id}")
    public ResponseEntity<?> updateRoute(@PathVariable int id,
                                         @RequestBody Map<String, Object> body,
                                         HttpSession session) {
        if (terminalId(session) == null) return forbidden();
        return bad("Operators cannot modify route records. Ask an admin to update routes.");
    }

    @PostMapping("/routes/{id}/request")
    public ResponseEntity<?> sendRouteRequest(@PathVariable int id,
                                              @RequestBody(required = false) Map<String, Object> body,
                                              HttpSession session) {
        Integer tid = terminalId(session);
        Integer uid = userId(session);
        if (tid == null || uid == null) return forbidden();

        // Find destination city from route
        List<Route> routes = svc.getRoutesForTerminal(tid);
        Route route = routes.stream().filter(r -> r.getId() == id).findFirst().orElse(null);
        if (route == null) return bad("Route not found.");

        String departureTime = body == null ? null : normalizeDateTime(firstPresent(body, "tripDepartureTime", "departureTime", "requestedDepartureTime"));
        String arrivalTime = body == null ? null : normalizeDateTime(firstPresent(body, "tripArrivalTime", "arrivalTime", "requestedArrivalTime"));
        if (departureTime == null || arrivalTime == null) {
            return bad("Departure and arrival time are required to send a trip request.");
        }
        Integer driverId = intBody(body, "driverId");
        Integer busId = intBody(body, "busId");
        java.math.BigDecimal price = decimalBody(body, "price");
        if (driverId == null || busId == null || price == null) {
            return bad("Driver, bus, and price are required to send a trip request.");
        }
        String error = svc.sendRouteRequest(id, uid, route.getDestination(), departureTime, arrivalTime, driverId, busId, price);
        boolean ok = error == null;
        return ok ? ok(Map.of("message","Request sent.")) : bad(error);
    }

    // ══ ROUTE REQUESTS ════════════════════════════════════════

    @GetMapping("/requests/incoming")
    public ResponseEntity<?> getIncoming(HttpSession session) {
        Integer uid = userId(session);
        if (uid == null) return forbidden();
        return ok(svc.getIncomingRequests(uid));
    }

    @GetMapping("/requests/outgoing")
    public ResponseEntity<?> getOutgoing(HttpSession session) {
        Integer uid = userId(session);
        if (uid == null) return forbidden();
        return ok(svc.getOutgoingRequests(uid));
    }

    @PutMapping("/requests/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable int id, HttpSession session) {
        if (userId(session) == null) return forbidden();
        String error = svc.approveRequest(id);
        return error == null
                ? ok(Map.of("message","Approved."))
                : bad(error);
    }

    @PutMapping("/requests/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable int id,
                                    @RequestBody Map<String, String> body,
                                    HttpSession session) {
        if (userId(session) == null) return forbidden();
        String reason = body.get("reason");
        if (reason == null || reason.isBlank()) return bad("Reason is required.");
        boolean ok = svc.rejectRequest(id, reason);
        return ok ? ok(Map.of("message","Rejected.")) : bad("Failed.");
    }

    // ══ DRIVERS ═══════════════════════════════════════════════

    @GetMapping("/drivers")
    public ResponseEntity<?> getDrivers(HttpSession session) {
        Integer tid = terminalId(session);
        if (tid == null) return forbidden();
        return ok(svc.getDriverAvailability(tid));
    }

    @GetMapping("/buses")
    public ResponseEntity<?> getTerminalBuses(@RequestParam(required = false) String date,
                                              HttpSession session) {
        Integer tid = terminalId(session);
        if (tid == null) return forbidden();
        return ok(svc.getTerminalBusAvailability(tid, date));
    }

    @PutMapping("/buses/{id}/status")
    public ResponseEntity<?> updateBusStatus(@PathVariable int id,
                                             @RequestBody Map<String, String> body,
                                             HttpSession session) {
        Integer tid = terminalId(session);
        if (tid == null) return forbidden();
        String status = body.get("status");
        Map<String, Object> result = svc.updateBusStatus(id, tid, status);
        return Boolean.TRUE.equals(result.get("success"))
                ? ok(result)
                : bad(String.valueOf(result.getOrDefault("message", "Failed to update bus status.")));
    }

    @PostMapping("/drivers")
    public ResponseEntity<?> addDriver(@RequestBody Map<String, String> body,
                                       HttpSession session) {
        Integer tid = terminalId(session);
        if (tid == null) return forbidden();

        String name  = body.get("fullName");
        String email = body.get("email");
        String pwd   = body.get("password");

        if (name==null||name.isBlank())  return bad("Name is required.");
        if (email==null||email.isBlank())return bad("Email is required.");
        if (pwd==null||pwd.length()<6)   return bad("Password too short.");

        boolean ok = svc.addDriver(name, email, pwd, tid);
        return ok ? ok(Map.of("message","Driver added.")) : bad("Email already exists.");
    }

    // ── Helpers ───────────────────────────────────────────────
    @GetMapping("/passenger-contacts")
    public ResponseEntity<?> passengerContacts(HttpSession session) {
        Integer uid = userId(session);
        if (uid == null) return forbidden();
        return ok(svc.passengerContacts(uid));
    }

    @PutMapping("/passenger-contacts/{id}/reply")
    public ResponseEntity<?> replyPassengerContact(@PathVariable int id,
                                                   @RequestBody Map<String, String> body,
                                                   HttpSession session) {
        Integer uid = userId(session);
        if (uid == null) return forbidden();
        String error = svc.replyToPassengerContact(id, uid, body.get("reply"));
        return error == null ? ok(Map.of("message", "Reply sent.")) : bad(error);
    }

    @DeleteMapping("/passenger-contacts/{id}")
    public ResponseEntity<?> deletePassengerContact(@PathVariable int id,
                                                    HttpSession session) {
        Integer uid = userId(session);
        if (uid == null) return forbidden();
        String error = svc.deletePassengerContact(id, uid);
        return error == null ? ok(Map.of("message", "Message removed.")) : bad(error);
    }

    @PutMapping("/passenger-contacts/{id}/delete")
    public ResponseEntity<?> hidePassengerContact(@PathVariable int id,
                                                  HttpSession session) {
        Integer uid = userId(session);
        if (uid == null) return forbidden();
        String error = svc.deletePassengerContact(id, uid);
        return error == null ? ok(Map.of("message", "Message removed.")) : bad(error);
    }

    private ResponseEntity<?> ok(Object data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }
    private ResponseEntity<?> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("success",false,"message",msg));
    }
    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(403).body(Map.of("success",false,"message","Forbidden"));
    }

    private String normalizeDateTime(String value) {
        if (value == null || value.isBlank()) return null;
        return value.length() >= 16 ? value.substring(0, 16).replace("T", " ") : value;
    }

    private String firstPresent(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            Object raw = body.get(key);
            String value = raw == null ? null : raw.toString();
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private Integer intBody(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) return null;
        try {
            String value = body.get(key).toString().trim();
            return value.isEmpty() ? null : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private java.math.BigDecimal decimalBody(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) return null;
        try {
            String value = body.get(key).toString().trim();
            return value.isEmpty() ? null : new java.math.BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
