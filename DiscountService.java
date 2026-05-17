package al.albus.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class DiscountService {

    // ── discount tiers ────────────────────────────────────────
    public static final int DISCOUNT_CHILD   = 50;   // age < 12
    public static final int DISCOUNT_STUDENT = 30;   // age 13–25 + student
    public static final int DISCOUNT_ELDERLY = 40;   // age 65+
    public static final int DISCOUNT_NONE    = 0;    // everyone else

    /**
     * Determine the discount percentage for a passenger.
     *
     * @param age       passenger's age (must be > 0)
     * @param isStudent true only when the student checkbox is ticked
     * @return discount percentage: 0, 30, 40, or 50
     */
    public int getDiscountPercent(int age, boolean isStudent) {
        if (age <= 0) {
            throw new IllegalArgumentException("Age must be a positive number.");
        }
        if (age <= 12) {
            return DISCOUNT_CHILD;           // under 12 → 50% off
        }
        if (age <= 25 && isStudent) {
            return DISCOUNT_STUDENT;         // student 13–25 → 30% off
        }
        if (age >= 65) {
            return DISCOUNT_ELDERLY;         // 65+ → 40% off
        }
        return DISCOUNT_NONE;                // full price
    }

    /**
     * Apply the discount to a base ticket price.
     *
     * @param basePrice  the full ticket price (e.g. 700.00)
     * @param discountPct the percentage from getDiscountPercent()
     * @return final price after discount, rounded to 2 decimal places
     */
    public BigDecimal applyDiscount(BigDecimal basePrice, int discountPct) {
        if (discountPct == 0) return basePrice;

        BigDecimal multiplier = BigDecimal.ONE
                .subtract(BigDecimal.valueOf(discountPct)
                        .divide(BigDecimal.valueOf(100)));

        return basePrice.multiply(multiplier)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Convenience method — get the final price in one call.
     *
     * @param basePrice  the full ticket price
     * @param age        passenger age
     * @param isStudent  student checkbox value
     * @return final price after applying the correct discount
     */
    public BigDecimal calculateFinalPrice(BigDecimal basePrice, int age, boolean isStudent) {
        int pct = getDiscountPercent(age, isStudent);
        return applyDiscount(basePrice, pct);
    }

    /**
     * Human-readable label for the discount applied — useful for the
     * booking confirmation screen (Eldrina's UI).
     */
    public String getDiscountLabel(int age, boolean isStudent) {
        int pct = getDiscountPercent(age, isStudent);
        return switch (pct) {
            case DISCOUNT_CHILD   -> "Child discount (under 12) — 50% off";
            case DISCOUNT_STUDENT -> "Student discount (13–25) — 30% off";
            case DISCOUNT_ELDERLY -> "Elderly discount (65+) — 40% off";
            default               -> "No discount — full price";
        };
    }
}