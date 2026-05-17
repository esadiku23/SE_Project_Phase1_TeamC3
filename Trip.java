package al.albus.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public class Trip {
    private int        id;
    private int        routeId;
    private int        busId;
    private int        driverId;
    private int        terminalId;
    private LocalDate  tripDate;
    private String     departureTime;
    private String     arrivalTime;
    private int        totalSeats;
    private int        availableSeats;
    private BigDecimal price;
    private String     status;   // scheduled | departed | arrived | cancelled

    // Joined fields (not persisted)
    private String origin;
    private String destination;
    private String driverName;
    private String busPlateNumber;
    private String busModel;

    public Trip() {}

    // ── getters ───────────────────────────────────────────────
    public int        getId()             { return id; }
    public int        getRouteId()        { return routeId; }
    public int        getBusId()          { return busId; }
    public int        getDriverId()       { return driverId; }
    public int        getTerminalId()     { return terminalId; }
    public LocalDate  getTripDate()       { return tripDate; }
    public String     getDepartureTime()  { return departureTime; }
    public String     getArrivalTime()    { return arrivalTime; }
    public int        getTotalSeats()     { return totalSeats; }
    public int        getAvailableSeats() { return availableSeats; }
    public BigDecimal getPrice()          { return price; }
    public String     getStatus()         { return status; }
    public String     getOrigin()         { return origin; }
    public String     getDestination()    { return destination; }
    public String     getDriverName()     { return driverName; }
    public String     getBusPlateNumber() { return busPlateNumber; }
    public String     getBusModel()       { return busModel; }

    // ── setters ───────────────────────────────────────────────
    public void setId(int id)                          { this.id = id; }
    public void setRouteId(int routeId)                { this.routeId = routeId; }
    public void setBusId(int busId)                    { this.busId = busId; }
    public void setDriverId(int driverId)              { this.driverId = driverId; }
    public void setTerminalId(int terminalId)          { this.terminalId = terminalId; }
    public void setTripDate(LocalDate tripDate)        { this.tripDate = tripDate; }
    public void setDepartureTime(String departureTime) { this.departureTime = departureTime; }
    public void setArrivalTime(String arrivalTime)     { this.arrivalTime = arrivalTime; }
    public void setTotalSeats(int totalSeats)          { this.totalSeats = totalSeats; }
    public void setAvailableSeats(int availableSeats)  { this.availableSeats = availableSeats; }
    public void setPrice(BigDecimal price)             { this.price = price; }
    public void setStatus(String status)               { this.status = status; }
    public void setOrigin(String origin)               { this.origin = origin; }
    public void setDestination(String destination)     { this.destination = destination; }
    public void setDriverName(String driverName)       { this.driverName = driverName; }
    public void setBusPlateNumber(String busPlateNumber) { this.busPlateNumber = busPlateNumber; }
    public void setBusModel(String busModel)           { this.busModel = busModel; }

    /** Convenience display string. */
    public String getRoute() {
        return (origin != null && destination != null)
                ? origin + " → " + destination : "—";
    }

    /** Booked seats count. */
    public int getBookedSeats() { return totalSeats - availableSeats; }
}
