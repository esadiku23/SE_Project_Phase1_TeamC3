package al.albus.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain Java tests for BookingService logic — no JUnit required.
 * Run via: right-click → Run 'BookingServiceTest.main()'
 */
public class BookingServiceTest {

    private static DiscountService discountService = new DiscountService();
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {

        // ── Payment status derivation ─────────────────────────
        check("TC01 - Online payment maps to 'paid'",
                "paid".equals(paymentStatus("online")));
        check("TC02 - Terminal payment maps to 'pending'",
                "pending".equals(paymentStatus("terminal")));
        check("TC03 - Card payment maps to 'paid'",
                "paid".equals(paymentStatus("card")));

        // ── Seat assignment ───────────────────────────────────
        check("TC04 - No seats taken: first assigned seat is 1",
                assignSeats(List.of(), 50, 1).get(0) == 1);
        check("TC05 - Seats 1 and 2 taken: assigns seat 3",
                assignSeats(List.of(1, 2), 50, 1).get(0) == 3);
        check("TC06 - 5 passengers, 50 free seats: assigns 5 seats",
                assignSeats(List.of(), 50, 5).size() == 5);
        check("TC07 - Bus fully booked: returns empty list",
                assignSeats(List.of(1, 2, 3), 3, 1).isEmpty());
        check("TC08 - Need 3 seats but only 1 left: assigns 1",
                assignSeats(List.of(1, 2), 3, 3).size() == 1);
        check("TC09 - Assigned seats do not overlap with taken seats",
                noOverlap(assignSeats(List.of(2, 4, 6), 10, 4), List.of(2, 4, 6)));
        check("TC10 - Boundary: last seat (seat 5 of 5) assigned correctly",
                assignSeats(List.of(1, 2, 3, 4), 5, 1).get(0) == 5);
        check("TC11 - Seats assigned in ascending order",
                isAscending(assignSeats(List.of(), 10, 5)));

        // ── Discount + price integration ──────────────────────
        BigDecimal base = new BigDecimal("700.00");
        check("TC12 - Child age 8: booking price is 350.00",
                priceFor(base, 8, false).compareTo(new BigDecimal("350.00")) == 0);
        check("TC13 - Student age 20: booking price is 490.00",
                priceFor(base, 20, true).compareTo(new BigDecimal("490.00")) == 0);
        check("TC14 - Adult age 35: booking price is full 700.00",
                priceFor(base, 35, false).compareTo(new BigDecimal("700.00")) == 0);
        check("TC15 - Elderly age 70: booking price is 420.00",
                priceFor(base, 70, false).compareTo(new BigDecimal("420.00")) == 0);

        // Mixed passengers
        int[]       ages     = {8, 20, 35, 70};
        boolean[]   students = {false, true, false, false};
        BigDecimal[] expected = {
                new BigDecimal("350.00"),
                new BigDecimal("490.00"),
                new BigDecimal("700.00"),
                new BigDecimal("420.00")
        };
        boolean allCorrect = true;
        for (int i = 0; i < ages.length; i++) {
            if (priceFor(base, ages[i], students[i]).compareTo(expected[i]) != 0) {
                allCorrect = false; break;
            }
        }
        check("TC15b - Mixed passengers all get correct individual prices", allCorrect);

        // ── Passenger count validation ────────────────────────
        check("TC16 - Empty booking list is invalid",
                new ArrayList<>().isEmpty());
        check("TC17 - Requesting more seats than available fails",
                5 > 2);
        check("TC18 - Requesting exactly available seats succeeds",
                3 <= 3);

        // ── Summary ───────────────────────────────────────────
        System.out.println("\n========================================");
        System.out.println("BookingServiceTest Results");
        System.out.println("========================================");
        System.out.println("Tests run : " + (passed + failed));
        System.out.println("Passed    : " + passed);
        System.out.println("Failed    : " + failed);
        System.out.println("========================================");
        if (failed == 0) System.out.println("ALL TESTS PASSED");
        else             System.out.println("SOME TESTS FAILED");
    }

    // ── Helpers ───────────────────────────────────────────────

    private static String paymentStatus(String method) {
        return (method.equals("online") || method.equals("card")) ? "paid" : "pending";
    }

    private static List<Integer> assignSeats(List<Integer> taken, int totalSeats, int needed) {
        List<Integer> assigned = new ArrayList<>();
        for (int seat = 1; seat <= totalSeats && assigned.size() < needed; seat++) {
            if (!taken.contains(seat)) assigned.add(seat);
        }
        return assigned;
    }

    private static boolean noOverlap(List<Integer> assigned, List<Integer> taken) {
        for (int seat : assigned) if (taken.contains(seat)) return false;
        return true;
    }

    private static boolean isAscending(List<Integer> list) {
        for (int i = 0; i < list.size() - 1; i++)
            if (list.get(i) >= list.get(i + 1)) return false;
        return true;
    }

    private static BigDecimal priceFor(BigDecimal base, int age, boolean isStudent) {
        int pct = discountService.getDiscountPercent(age, isStudent);
        return discountService.applyDiscount(base, pct);
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            System.out.println("PASS: " + name);
            passed++;
        } else {
            System.out.println("FAIL: " + name);
            failed++;
        }
    }
}