package al.albus.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class Booking {
    private int    id;
    private int    passengerId;
    private int    tripId;
    private String paymentMethod;   // "online" | "terminal"
    private String paymentStatus;   // "paid"   | "pending"
    private LocalDateTime bookedAt;

    // passenger detail fields (from passengers_detail table)
    private String     firstName;
    private String     lastName;
    private String     phone;
    private int        age;
    private int        seatNumber;
    private int        discountPct;
    private BigDecimal finalPrice;
    private boolean    specialNeeds;

    public Booking() {}

    // ── getters & setters ─────────────────────────────────────
    public int    getId()              { return id; }
    public int    getPassengerId()     { return passengerId; }
    public int    getTripId()          { return tripId; }
    public String getPaymentMethod()   { return paymentMethod; }
    public String getPaymentStatus()   { return paymentStatus; }
    public LocalDateTime getBookedAt() { return bookedAt; }
    public String     getFirstName()   { return firstName; }
    public String     getLastName()    { return lastName; }
    public String     getPhone()       { return phone; }
    public int        getAge()         { return age; }
    public int        getSeatNumber()  { return seatNumber; }
    public int        getDiscountPct() { return discountPct; }
    public BigDecimal getFinalPrice()  { return finalPrice; }
    public boolean    isSpecialNeeds() { return specialNeeds; }

    public void setId(int id)                          { this.id = id; }
    public void setPassengerId(int passengerId)        { this.passengerId = passengerId; }
    public void setTripId(int tripId)                  { this.tripId = tripId; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public void setPaymentStatus(String paymentStatus) { this.paymentStatus = paymentStatus; }
    public void setBookedAt(LocalDateTime bookedAt)    { this.bookedAt = bookedAt; }
    public void setFirstName(String firstName)         { this.firstName = firstName; }
    public void setLastName(String lastName)           { this.lastName = lastName; }
    public void setPhone(String phone)                 { this.phone = phone; }
    public void setAge(int age)                        { this.age = age; }
    public void setSeatNumber(int seatNumber)          { this.seatNumber = seatNumber; }
    public void setDiscountPct(int discountPct)        { this.discountPct = discountPct; }
    public void setFinalPrice(BigDecimal finalPrice)   { this.finalPrice = finalPrice; }
    public void setSpecialNeeds(boolean specialNeeds)  { this.specialNeeds = specialNeeds; }
}