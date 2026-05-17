package al.albus.service;

import java.math.BigDecimal;

/**
 * Plain Java tests for DiscountService — no JUnit required.
 * Run via: right-click → Run 'DiscountServiceTest.main()'
 */
public class DiscountServiceTest {

    private static DiscountService service = new DiscountService();
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {

        // ── getDiscountPercent — Normal ───────────────────────
        check("TC01 - Child aged 8 gets 50% discount",
                service.getDiscountPercent(8, false) == 50);
        check("TC02 - Student aged 20 gets 30% discount",
                service.getDiscountPercent(20, true) == 30);
        check("TC03 - Elderly aged 70 gets 40% discount",
                service.getDiscountPercent(70, false) == 40);
        check("TC04 - Adult aged 35 gets 0% discount",
                service.getDiscountPercent(35, false) == 0);
        check("TC05 - Adult aged 26 with student flag gets 0%",
                service.getDiscountPercent(26, true) == 0);

        // ── getDiscountPercent — Boundary ─────────────────────
        check("TC06 - Boundary: age 12 gets child discount (50%)",
                service.getDiscountPercent(12, false) == 50);
        check("TC07 - Boundary: age 13 non-student gets 0%",
                service.getDiscountPercent(13, false) == 0);
        check("TC08 - Boundary: age 13 student gets 30%",
                service.getDiscountPercent(13, true) == 30);
        check("TC09 - Boundary: age 25 student gets 30%",
                service.getDiscountPercent(25, true) == 30);
        check("TC10 - Boundary: age 65 gets elderly discount (40%)",
                service.getDiscountPercent(65, false) == 40);
        check("TC11 - Boundary: age 64 non-student gets 0%",
                service.getDiscountPercent(64, false) == 0);
        check("TC12 - Boundary: age 1 gets child discount (50%)",
                service.getDiscountPercent(1, false) == 50);

        // ── getDiscountPercent — Invalid ──────────────────────
        check("TC13 - Age 0 throws IllegalArgumentException",
                throwsException(() -> service.getDiscountPercent(0, false)));
        check("TC14 - Age -5 throws IllegalArgumentException",
                throwsException(() -> service.getDiscountPercent(-5, false)));

        // ── applyDiscount ─────────────────────────────────────
        BigDecimal base = new BigDecimal("700.00");
        check("TC15 - 0% discount returns full price 700.00",
                service.applyDiscount(base, 0).compareTo(new BigDecimal("700.00")) == 0);
        check("TC16 - 50% discount returns 350.00",
                service.applyDiscount(base, 50).compareTo(new BigDecimal("350.00")) == 0);
        check("TC17 - 30% discount returns 490.00",
                service.applyDiscount(base, 30).compareTo(new BigDecimal("490.00")) == 0);
        check("TC18 - 40% discount returns 420.00",
                service.applyDiscount(base, 40).compareTo(new BigDecimal("420.00")) == 0);
        check("TC19 - Result rounded to 2 decimal places",
                service.applyDiscount(new BigDecimal("999.99"), 30).scale() == 2);

        // ── calculateFinalPrice ───────────────────────────────
        check("TC20 - Child aged 8 pays 350.00",
                service.calculateFinalPrice(base, 8, false).compareTo(new BigDecimal("350.00")) == 0);
        check("TC21 - Student aged 20 pays 490.00",
                service.calculateFinalPrice(base, 20, true).compareTo(new BigDecimal("490.00")) == 0);
        check("TC22 - Elderly aged 70 pays 420.00",
                service.calculateFinalPrice(base, 70, false).compareTo(new BigDecimal("420.00")) == 0);
        check("TC23 - Adult aged 35 pays full price 700.00",
                service.calculateFinalPrice(base, 35, false).compareTo(new BigDecimal("700.00")) == 0);

        // ── getDiscountLabel ──────────────────────────────────
        check("TC24 - Child discount label correct",
                "Child discount (under 12) \u2014 50% off".equals(service.getDiscountLabel(8, false)));
        check("TC25 - Student discount label correct",
                "Student discount (13\u201325) \u2014 30% off".equals(service.getDiscountLabel(20, true)));
        check("TC26 - Elderly discount label correct",
                "Elderly discount (65+) \u2014 40% off".equals(service.getDiscountLabel(70, false)));
        check("TC27 - No discount label correct",
                "No discount \u2014 full price".equals(service.getDiscountLabel(35, false)));

        // ── Summary ───────────────────────────────────────────
        System.out.println("\n========================================");
        System.out.println("DiscountServiceTest Results");
        System.out.println("========================================");
        System.out.println("Tests run : " + (passed + failed));
        System.out.println("Passed    : " + passed);
        System.out.println("Failed    : " + failed);
        System.out.println("========================================");
        if (failed == 0) System.out.println("ALL TESTS PASSED");
        else             System.out.println("SOME TESTS FAILED");
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

    private static boolean throwsException(Runnable r) {
        try { r.run(); return false; }
        catch (IllegalArgumentException e) { return true; }
    }
}