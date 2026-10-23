package com.mangle.retailshopapp.settlement;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The paisa guard, shared by every line that holds money.
 *
 * <p>⚠ NOT a {@code Money} type, and deliberately not one: design-api.md §5.2
 * decided against one because {@code BigDecimal} at scale 2 already matches
 * {@code decimal(38,2)} and every existing DTO. This is the one validation
 * those lines agreed on, in one place rather than copied into each record that
 * needs it.
 */
public final class Amounts {

    /** Scale 2, so it compares equal to what the column holds. */
    public static final BigDecimal ZERO = new BigDecimal("0.00");

    private Amounts() {
    }

    /**
     * Normalises an amount to the paisa, or refuses it.
     *
     * <p>Money finer than a paisa is a programming error rather than something
     * to round away: rounding it would mean the figure the customer was shown
     * and the figure posted to the ledger could differ by construction.
     *
     * @param value what to check; {@code null} reads as zero
     * @param what  the field name, so the message names it
     */
    public static BigDecimal requireScale2(BigDecimal value, String what) {
        if (value == null) {
            return ZERO;
        }
        if (value.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(
                    what + " is held to the paisa; got " + value.toPlainString());
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }
}
