package al.albus.model;

public class RouteRequest {
    private int    id;
    private int    routeId;
    private int    fromTerminalId;
    private int    toTerminalId;
    private String status;           // pending | approved | rejected
    private String rejectionReason;

    // Joined display fields
    private String origin;
    private String destination;
    private String fromCity;
    private String requestedDepartureTime;
    private String requestedArrivalTime;
    private int    plannedDriverId;
    private int    plannedBusId;
    private java.math.BigDecimal plannedPrice;
    private String createdAt;
    private int    distanceKm;

    public RouteRequest() {}

    public int    getId()               { return id; }
    public int    getRouteId()          { return routeId; }
    public int    getFromTerminalId()   { return fromTerminalId; }
    public int    getToTerminalId()     { return toTerminalId; }
    public String getStatus()           { return status; }
    public String getRejectionReason()  { return rejectionReason; }
    public String getOrigin()           { return origin; }
    public String getDestination()      { return destination; }
    public String getFromCity()         { return fromCity; }
    public String getRequestedDepartureTime() { return requestedDepartureTime; }
    public String getRequestedArrivalTime()   { return requestedArrivalTime; }
    public int    getPlannedDriverId()        { return plannedDriverId; }
    public int    getPlannedBusId()           { return plannedBusId; }
    public java.math.BigDecimal getPlannedPrice() { return plannedPrice; }
    public String getCreatedAt()              { return createdAt; }
    public int    getDistanceKm()       { return distanceKm; }

    public void setId(int id)                              { this.id = id; }
    public void setRouteId(int routeId)                    { this.routeId = routeId; }
    public void setFromTerminalId(int fromTerminalId)      { this.fromTerminalId = fromTerminalId; }
    public void setToTerminalId(int toTerminalId)          { this.toTerminalId = toTerminalId; }
    public void setStatus(String status)                   { this.status = status; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    public void setOrigin(String origin)                   { this.origin = origin; }
    public void setDestination(String destination)         { this.destination = destination; }
    public void setFromCity(String fromCity)               { this.fromCity = fromCity; }
    public void setRequestedDepartureTime(String requestedDepartureTime) { this.requestedDepartureTime = requestedDepartureTime; }
    public void setRequestedArrivalTime(String requestedArrivalTime)     { this.requestedArrivalTime = requestedArrivalTime; }
    public void setPlannedDriverId(int plannedDriverId)                  { this.plannedDriverId = plannedDriverId; }
    public void setPlannedBusId(int plannedBusId)                        { this.plannedBusId = plannedBusId; }
    public void setPlannedPrice(java.math.BigDecimal plannedPrice)       { this.plannedPrice = plannedPrice; }
    public void setCreatedAt(String createdAt)              { this.createdAt = createdAt; }
    public void setDistanceKm(int distanceKm)              { this.distanceKm = distanceKm; }

    public String getRouteLabel() { return origin + " → " + destination; }
}
