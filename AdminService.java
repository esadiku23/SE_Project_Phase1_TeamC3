package al.albus.service;

import al.albus.model.Driver;
import al.albus.model.Bus;
import al.albus.model.Route;
import al.albus.model.Trip;
import al.albus.model.User;
import al.albus.repository.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class AdminService {

    private static final String LOW_PASSENGER_CHANGE_MESSAGE =
            "Your bus schedule has changed. You may choose another departure at no extra cost, or cancel your booking.";
    private static final String PASSENGER_TRIP_CHANGE_MESSAGE =
            "Your bus schedule has changed. You may choose another departure at no extra cost, or cancel your booking.";

    private final AdminRepository      adminRepo        = new AdminRepository();
    private final RouteRepository      routeRepo        = new RouteRepository();
    private final TripRepository       tripRepo         = new TripRepository();
    private final DriverRepository     driverRepo       = new DriverRepository();
    private final BusRepository        busRepo          = new BusRepository();
    private final PasswordService      passwordService  = new PasswordService();

    // ─────────────────────────────────────────────
    //  Statistics
    // ─────────────────────────────────────────────

    public Map<String, Object> stats() {
        return adminRepo.stats();
    }

    // ─────────────────────────────────────────────
    //  Terminals
    // ─────────────────────────────────────────────

    public List<Map<String, Object>> terminals() {
        return adminRepo.findTerminals();
    }

    public List<Map<String, Object>> terminalOverview() {
        return adminRepo.terminalOverview();
    }

    // ─────────────────────────────────────────────
    //  Account management  (staff only)
    // ─────────────────────────────────────────────

    /**
     * Returns operator and driver accounts only.
     * Acceptable role values: "all", "operator", "driver".
     * Any other value (including "passenger") returns the full staff list.
     */
    public List<User> staff(String role) {
        return adminRepo.findStaff(role);
    }

    /**
     * Validates inputs before creating a staff account.
     *
     * @return null if valid, otherwise a human-readable error message
     */
    public String validateStaffCreate(String fullName, String email, String password, int terminalId) {
        if (!adminRepo.terminalExists(terminalId)) return "Terminal does not exist.";
        if (adminRepo.emailExists(email))          return "This email is already registered.";
        return null;
    }

    public boolean addOperator(String fullName, String email, String password, int terminalId) {
        boolean created = adminRepo.createOperator(fullName, email, passwordService.hash(password), terminalId);
        if (created) {
            adminRepo.notifyTerminalStaff(
                    terminalId,
                    "Admin added a new operator to your terminal: " + fullName + "."
            );
        }
        return created;
    }

    public boolean addDriver(String fullName, String email, String password, int terminalId) {
        boolean created = adminRepo.createDriver(fullName, email, passwordService.hash(password), terminalId);
        if (created) {
            adminRepo.notifyTerminalStaff(
                    terminalId,
                    "Admin added a new driver to your terminal: " + fullName + "."
            );
        }
        return created;
    }

    /**
     * Deactivates a staff account (operator or driver only).
     *
     * @return null on success, error message on failure
     */
    public String deactivateUser(int userId) {
        Optional<User> maybeUser = adminRepo.findUserById(userId);
        if (maybeUser.isEmpty()) return "User not found.";
        User user = maybeUser.get();

        if ("admin".equals(user.getRole()))
            return "Admin accounts cannot be deactivated.";
        if (!"operator".equals(user.getRole()) && !"driver".equals(user.getRole()))
            return "Only operator and driver accounts can be deactivated.";
        if (!user.isActive())
            return "This account is already deactivated.";

        boolean deactivated = adminRepo.deactivateUser(userId);
        if (deactivated) {
            adminRepo.sendNotificationToUser(userId, "Admin deactivated your ALBus staff account.");
            if (user.getTerminalId() != null) {
                adminRepo.notifyTerminalStaff(
                        user.getTerminalId(),
                        "Admin deactivated " + user.getFullName() + "'s " + user.getRole() + " account."
                );
            }
        }
        return deactivated ? null : "Failed to deactivate account.";
    }

    // ─────────────────────────────────────────────
    //  Terminal capacity
    // ─────────────────────────────────────────────

    public List<Map<String, Object>> terminalCapacity() {
        return adminRepo.terminalCapacity();
    }

    /**
     * @return null on success, error message on failure
     */
    public String updateTerminalCapacity(int terminalId, int maxBusesDay) {
        if (!adminRepo.terminalExists(terminalId)) return "Terminal does not exist.";
        if (maxBusesDay < 0)                       return "Capacity cannot be negative.";
        boolean updated = adminRepo.updateTerminalCapacity(terminalId, maxBusesDay);
        if (updated) {
            adminRepo.notifyTerminalStaff(
                    terminalId,
                    "Admin updated your terminal's daily trip capacity to " + maxBusesDay + "."
            );
        }
        return updated ? null : "Failed to update terminal capacity.";
    }

    // ─────────────────────────────────────────────
    //  Notifications / Messages
    // ─────────────────────────────────────────────

    /** All notifications, newest-first, enriched with recipient info. */
    public List<Map<String, Object>> notifications() {
        return adminRepo.findAllNotifications();
    }

    /**
     * Sends a manual message from admin to an operator or driver.
     *
     * @return null on success, error message on failure
     */
    public String sendNotification(int userId, String message) {
        Optional<User> maybeUser = adminRepo.findUserById(userId);
        if (maybeUser.isEmpty()) return "User not found.";
        User user = maybeUser.get();

        if (!user.isActive())
            return "Cannot message a deactivated account.";
        if (!"operator".equals(user.getRole()) && !"driver".equals(user.getRole()))
            return "Messages can only be sent to operators and drivers.";
        if (message == null || message.isBlank())
            return "Message is required.";

        return adminRepo.sendNotificationToUser(userId, message.trim()) != -1
                ? null
                : "Failed to send message.";
    }

    // ─────────────────────────────────────────────
    //  Drivers
    // ─────────────────────────────────────────────

    public List<Driver> driversForTerminal(int terminalId) {
        return driverRepo.findByTerminal(terminalId);
    }

    public List<Bus> buses() {
        return busRepo.findAll();
    }

    public List<Bus> busesForTerminal(int terminalId) {
        return busRepo.findByTerminal(terminalId);
    }

    public int addBus(int terminalId, String plateNumber, String model, int seatCount, String status) {
        if (!adminRepo.terminalExists(terminalId)) return -2;
        if (seatCount <= 0) return -3;
        return busRepo.create(terminalId, plateNumber, model, seatCount, "available");
    }

    public boolean updateBus(int id, int terminalId, String plateNumber, String model, int seatCount, String status) {
        if (!adminRepo.terminalExists(terminalId) || seatCount <= 0) return false;
        return busRepo.update(id, terminalId, plateNumber, model, seatCount, status);
    }

    public boolean deactivateBus(int id) {
        return busRepo.deactivate(id);
    }

    // ─────────────────────────────────────────────
    //  Routes
    // ─────────────────────────────────────────────

    public List<Route> routes() {
        return routeRepo.findAll();
    }

    public int cleanupRequestedRoutes() {
        return routeRepo.removeRequestedRoutesWithoutTrips();
    }

    /** @return new route id, or -1 on failure */
    public int addRoute(int originTerminalId, String destination, int distanceKm) {
        String origin = routeRepo.findTerminalCityById(originTerminalId);
        if (origin == null || origin.isBlank()) return -1;
        int routeId = routeRepo.createRoute(originTerminalId, origin, destination, distanceKm);
        if (routeId != -1) {
            adminRepo.notifyTerminalStaff(
                    originTerminalId,
                    "Admin created a new route from your terminal to " + destination + "."
            );
        }
        return routeId;
    }

    public boolean updateRoute(int routeId, String destination, int distanceKm) {
        int originTerminalId = routeRepo.findOriginTerminalIdByRouteId(routeId);
        boolean updated = routeRepo.updateRoute(routeId, "", destination, distanceKm);
        if (updated && originTerminalId != -1) {
            adminRepo.notifyTerminalStaff(
                    originTerminalId,
                    "Admin updated a route from your terminal to " + destination + "."
            );
        }
        return updated;
    }

    // ─────────────────────────────────────────────
    //  Trips  (structural data only — no status changes)
    // ─────────────────────────────────────────────

    public List<Trip> trips() {
        return tripRepo.findAll();
    }

    /**
     * Creates a new trip and notifies the driver and terminal operator(s).
     *
     * @return new trip id; -3 if terminal capacity exceeded; -1 on other failure
     */
    public int addTrip(int routeId, int busId, int driverId, int originTerminalId, LocalDate date,
                       String departure, String arrival, BigDecimal price) {
        if (!adminRepo.hasTerminalCapacity(originTerminalId, date.toString())) return -3;
        if (!busRepo.isAssignableToTerminal(busId, originTerminalId)) return -4;
        int seats = busRepo.seatCount(busId);

        Trip trip = buildTrip(0, routeId, driverId, originTerminalId, date,
                departure, arrival, seats, price);
        trip.setBusId(busId);
        trip.setStatus("scheduled");

        int tripId = tripRepo.createTrip(trip);
        if (tripId != -1) {
            adminRepo.notifyTripChanged(tripId, "Admin created a new trip assigned to you.");
        }
        return tripId;
    }

    /**
     * Updates structural trip details and notifies affected driver and operator(s).
     * Does NOT touch status — operational status belongs to drivers/operators.
     */
    public boolean updateTrip(int tripId, int routeId, int busId, int driverId, int originTerminalId,
                              LocalDate date, String departure, String arrival,
                              BigDecimal price) {
        if (!adminRepo.hasTerminalCapacity(originTerminalId, date.toString(), tripId)) return false;
        if (!busRepo.isAssignableToTerminal(busId, originTerminalId)) return false;
        int seats = busRepo.seatCount(busId);

        int[] previousAssignment = tripRepo.findAssignmentById(tripId);
        boolean scheduleChanged = tripRepo.hasScheduleTimeChange(tripId, departure, arrival);
        String currentStatus = tripRepo.findStatusById(tripId);
        Trip trip = buildTrip(tripId, routeId, driverId, originTerminalId, date,
                departure, arrival, seats, price);
        trip.setBusId(busId);
        boolean updated = tripRepo.updateTripDetails(trip);
        if (updated) {
            Integer previousDriverId = previousAssignment == null ? null : previousAssignment[0];
            Integer previousTerminalId = previousAssignment == null ? null : previousAssignment[1];
            adminRepo.notifyTripChanged(
                    tripId,
                    previousDriverId,
                    previousTerminalId,
                    "Admin updated the details for one of your trips."
            );
            notifyPassengersForTripChange(tripId);
        }
        return updated;
    }

    public boolean transitionTripStatus(int tripId, String targetStatus) {
        String currentStatus = tripRepo.findStatusById(tripId);
        targetStatus = targetStatus == null ? null : targetStatus.toLowerCase().trim();
        if (currentStatus == null || targetStatus == null) return false;

        boolean legal = "cancelled".equals(targetStatus)
                && ("scheduled".equals(currentStatus) || "departed".equals(currentStatus));
        if (!legal) return false;

        boolean updated = tripRepo.updateStatus(tripId, targetStatus);
        if (updated) {
            adminRepo.notifyTripChanged(tripId, "Admin cancelled one of your trips.");
            notifyPassengersForTripChange(tripId);
        }
        return updated;
    }

    // ─────────────────────────────────────────────
    //  Internal helpers
    // ─────────────────────────────────────────────

    private Trip buildTrip(int id, int routeId, int driverId, int originTerminalId,
                           LocalDate date, String departure, String arrival,
                           int seats, BigDecimal price) {
        Trip trip = new Trip();
        trip.setId(id);
        trip.setRouteId(routeId);
        trip.setDriverId(driverId);
        trip.setTerminalId(originTerminalId);
        trip.setTripDate(date);
        trip.setDepartureTime(departure);
        trip.setArrivalTime(arrival);
        trip.setTotalSeats(seats);
        trip.setAvailableSeats(seats);
        trip.setPrice(price);
        return trip;
    }

    private void notifyLowPassengerScheduleChange(int tripId, String currentStatus, boolean scheduleChanged) {
        int passengers = tripRepo.countPassengers(tripId);
        if (scheduleChanged && "scheduled".equals(currentStatus) && passengers <= 2) {
            adminRepo.notifyPassengersForTrip(tripId, LOW_PASSENGER_CHANGE_MESSAGE);
        }
    }

    private void notifyPassengersForTripChange(int tripId) {
        adminRepo.notifyPassengersForTrip(tripId, PASSENGER_TRIP_CHANGE_MESSAGE);
    }
}
