package al.albus.model;

public class Bus {
    private int id;
    private int terminalId;
    private String terminalName;
    private String plateNumber;
    private String model;
    private int seatCount;
    private String status;
    private boolean active;

    public int getId() { return id; }
    public int getTerminalId() { return terminalId; }
    public String getTerminalName() { return terminalName; }
    public String getPlateNumber() { return plateNumber; }
    public String getModel() { return model; }
    public int getSeatCount() { return seatCount; }
    public String getStatus() { return status; }
    public boolean isActive() { return active; }

    public void setId(int id) { this.id = id; }
    public void setTerminalId(int terminalId) { this.terminalId = terminalId; }
    public void setTerminalName(String terminalName) { this.terminalName = terminalName; }
    public void setPlateNumber(String plateNumber) { this.plateNumber = plateNumber; }
    public void setModel(String model) { this.model = model; }
    public void setSeatCount(int seatCount) { this.seatCount = seatCount; }
    public void setStatus(String status) { this.status = status; }
    public void setActive(boolean active) { this.active = active; }
}
