package al.albus.model;

import java.time.LocalDateTime;

public class User {
    private int id;
    private String fullName;
    private String email;
    private String passwordHash;
    private String role;           // "operator" | "driver" | "passenger"
    private Integer terminalId;    // null for passengers
    private LocalDateTime createdAt;
    private boolean active = true;

    // ── constructors ──────────────────────────────────────────
    public User() {}

    public User(int id, String fullName, String email,
                String passwordHash, String role,
                Integer terminalId, LocalDateTime createdAt) {
        this.id           = id;
        this.fullName     = fullName;
        this.email        = email;
        this.passwordHash = passwordHash;
        this.role         = role;
        this.terminalId   = terminalId;
        this.createdAt    = createdAt;
    }

    // ── getters / setters ─────────────────────────────────────
    public int getId()                    { return id; }
    public String getFullName()           { return fullName; }
    public String getEmail()              { return email; }
    public String getPasswordHash()       { return passwordHash; }
    public String getRole()               { return role; }
    public Integer getTerminalId()        { return terminalId; }
    public LocalDateTime getCreatedAt()   { return createdAt; }
    public boolean isActive()             { return active; }

    public void setId(int id)                        { this.id = id; }
    public void setFullName(String fullName)         { this.fullName = fullName; }
    public void setEmail(String email)               { this.email = email; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public void setRole(String role)                 { this.role = role; }
    public void setTerminalId(Integer terminalId)    { this.terminalId = terminalId; }
    public void setCreatedAt(LocalDateTime t)        { this.createdAt = t; }
    public void setActive(boolean active)             { this.active = active; }
}
