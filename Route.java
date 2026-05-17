package al.albus.model;

public class Route {
    private int    id;
    private int    originTerminalId;
    private String origin;
    private String destination;
    private int    distanceKm;
    private String status;   // pending | approved | rejected

    public Route() {}

    public int    getId()               { return id; }
    public int    getOriginTerminalId() { return originTerminalId; }
    public String getOrigin()           { return origin; }
    public String getDestination()      { return destination; }
    public int    getDistanceKm()       { return distanceKm; }
    public String getStatus()           { return status; }

    public void setId(int id)                              { this.id = id; }
    public void setOriginTerminalId(int originTerminalId)  { this.originTerminalId = originTerminalId; }
    public void setOrigin(String origin)                   { this.origin = origin; }
    public void setDestination(String destination)         { this.destination = destination; }
    public void setDistanceKm(int distanceKm)              { this.distanceKm = distanceKm; }
    public void setStatus(String status)                   { this.status = status; }

    public String getLabel() { return origin + " → " + destination; }
}