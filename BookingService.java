package al.albus.service;

import al.albus.model.Booking;
import al.albus.repository.BookingRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class BookingService {

    private final BookingRepository bookingRepo    = new BookingRepository();
    private final DiscountService   discountService = new DiscountService();

    /**
     * Book one or more passengers onto a trip in a single session.
     *
     * Each Booking object in the list must have these fields set BEFORE calling:
     *   passengerId, tripId, paymentMethod,
     *   firstName, lastName, phone, age, specialNeeds, isStudent (via discount logic)
     *
     * The service will:
     *   1. Auto-assign seat numbers
     *   2. Calculate discount + final price
     *   3. Insert into bookings + passengers_detail
     *   4. Decrement available_seats on the trip
     *
     * @param bookings    list of partially-filled Booking objects (one per passenger)
     * @param basePrice   the trip's full ticket price
     * @param isStudent   parallel list — true if the corresponding passenger ticked student
     * @return list of created booking IDs, or empty list if booking failed
     */
    public List<Integer> bookMultiple(List<Booking> bookings,
                                      BigDecimal basePrice,
                                      List<Boolean> isStudent) {

        int tripId     = bookings.get(0).getTripId();
        int count      = bookings.size();
        List<Integer> createdIds = new ArrayList<>();

        // ── 1. Auto-assign seats ──────────────────────────────
        List<Integer> takenSeats = bookingRepo.getTakenSeats(tripId);
        int           totalSeats = bookingRepo.getTotalSeats(tripId);
        List<Integer> assignedSeats = assignSeats(takenSeats, totalSeats, count);

        if (assignedSeats.size() < count) {
            System.out.println("Not enough available seats for this booking.");
            return createdIds;   // empty — caller should show error
        }

        // ── 2. Insert each passenger ──────────────────────────
        for (int i = 0; i < count; i++) {
            Booking b = bookings.get(i);

            // discount + price
            int        pct        = discountService.getDiscountPercent(b.getAge(), isStudent.get(i));
            BigDecimal finalPrice = discountService.applyDiscount(basePrice, pct);
            int        seat       = assignedSeats.get(i);

            // payment status: online → "paid" (after card sim), terminal → "pending"
            String paymentStatus = b.getPaymentMethod().equals("online") ? "paid" : "pending";

            // insert bookings row
            int bookingId = bookingRepo.createBooking(
                    b.getPassengerId(), tripId, b.getPaymentMethod(), paymentStatus);

            if (bookingId == -1) {
                System.out.println("Failed to create booking for passenger: " + b.getFirstName());
                continue;
            }

            // insert passengers_detail row
            bookingRepo.createPassengerDetail(
                    bookingId,
                    b.getFirstName(), b.getLastName(), b.getPhone(),
                    b.getAge(), seat, pct, finalPrice, b.isSpecialNeeds());

            b.setSeatNumber(seat);
            b.setDiscountPct(pct);
            b.setFinalPrice(finalPrice);
            b.setPaymentStatus(paymentStatus);

            createdIds.add(bookingId);
        }

        // ── 3. Decrement available_seats once for all tickets ─
        if (!createdIds.isEmpty()) {
            boolean updated = bookingRepo.decrementSeats(tripId, createdIds.size());
            if (!updated) {
                System.out.println("Warning: seat count could not be decremented.");
            }
        }

        return createdIds;
    }

    /**
     * Update a booking's payment status to "paid" (called after card payment succeeds).
     */
    public boolean markAsPaid(int bookingId) {
        return bookingRepo.updatePaymentStatus(bookingId, "paid");
    }

    // ── private helpers ───────────────────────────────────────

    /**
     * Find the next N available seat numbers not already taken.
     * Seats are numbered 1..totalSeats.
     */
    private List<Integer> assignSeats(List<Integer> taken, int totalSeats, int needed) {
        List<Integer> assigned = new ArrayList<>();
        for (int seat = 1; seat <= totalSeats && assigned.size() < needed; seat++) {
            if (!taken.contains(seat)) {
                assigned.add(seat);
            }
        }
        return assigned;
    }
}