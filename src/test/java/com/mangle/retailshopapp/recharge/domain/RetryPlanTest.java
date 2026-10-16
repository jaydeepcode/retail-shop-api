package com.mangle.retailshopapp.recharge.domain;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The retry arithmetic, with no database and no Spring.
 *
 * <p>§5.4 rule 4's point: the matrix that matters here is arithmetic — same
 * amount, lower, higher, and what the surcharge does across all three — and it
 * should not need a container to run.
 */
@DisplayName("Retry arithmetic")
class RetryPlanTest {

    @Test
    @DisplayName("the same amount settles nothing in either direction")
    void sameAmount() {
        RetryPlan plan = plan("219.00", "0.00", "219.00");

        assertEquals(new BigDecimal("0.00"), plan.differenceOwed());
        assertEquals(new BigDecimal("0.00"), plan.topUpDue());
        assertTrue(plan.settlesNothing(),
                "the common retry hands nothing over and must stay free of ceremony");
    }

    @Test
    @DisplayName("a lower retry owes the difference back")
    void lowerAmount() {
        // §3.3's worked example: Rs 219 retried at Rs 199 owes Rs 20.
        RetryPlan plan = plan("219.00", "0.00", "199.00");

        assertEquals(new BigDecimal("20.00"), plan.differenceOwed());
        assertEquals(new BigDecimal("0.00"), plan.topUpDue());
        assertFalse(plan.settlesNothing());
    }

    @Test
    @DisplayName("a higher retry takes the difference as a top-up")
    void higherAmount() {
        // log §7.4's opposite direction: Rs 650 against Rs 600 collected.
        RetryPlan plan = plan("600.00", "0.00", "650.00");

        assertEquals(new BigDecimal("0.00"), plan.differenceOwed());
        assertEquals(new BigDecimal("50.00"), plan.topUpDue());
    }

    @Test
    @DisplayName("the surcharge rides along and never enters the difference")
    void surchargeDoesNotMove() {
        // ⚠ THE CASE BETWEEN THE THREE ABOVE, and the one a wrong implementation
        // passes the others without. A DTH line carries Rs 10 the shop charged
        // for taking the sale; the operator failing to deliver does not change
        // what the shop did, so the fee is not refunded piecemeal and the whole
        // difference falls on the face value. Computing the difference from LINE
        // TOTALS instead would give the same number here — which is exactly why
        // the identity, not the number, is what gets asserted.
        RetryPlan plan = plan("240.00", "10.00", "200.00");

        assertEquals(new BigDecimal("40.00"), plan.differenceOwed(),
                "the face value fell by 40, and that is the whole difference");
        assertEquals(new BigDecimal("10.00"), plan.surcharge(),
                "the fee is unchanged by the retry");
        assertEquals(new BigDecimal("250.00"), plan.originalLineTotal());
        assertEquals(new BigDecimal("210.00"), plan.retryLineTotal(),
                "the retry will consume its own amount plus the same fee");

        // The invariant that makes the residual correct: what stays in
        // RCHG_PENDING is exactly what is owed back.
        assertEquals(plan.differenceOwed(),
                plan.originalLineTotal().subtract(plan.retryLineTotal()),
                "the residual IS the unspent part of what the customer paid");
    }

    @Test
    @DisplayName("the same identity holds when the retry is higher")
    void surchargeIdentityUpward() {
        RetryPlan plan = plan("240.00", "10.00", "300.00");

        assertEquals(new BigDecimal("60.00"), plan.topUpDue());
        assertEquals(plan.topUpDue(),
                plan.retryLineTotal().subtract(plan.originalLineTotal()),
                "the top-up IS what the retry will consume beyond what was collected");
    }

    @Test
    @DisplayName("one paisa either way is a difference, not a rounding")
    void onePaisa() {
        assertEquals(new BigDecimal("0.01"), plan("199.00", "0.00", "198.99").differenceOwed());
        assertEquals(new BigDecimal("0.01"), plan("199.00", "0.00", "199.01").topUpDue());
    }

    @Test
    @DisplayName("a null surcharge is zero, not a failure")
    void nullSurcharge() {
        // Every row V15 backfills has one, but a hand-built fixture may not, and
        // a NullPointerException here would be a 500 on a real retry.
        RetryPlan plan = RetryPlan.of(new BigDecimal("199.00"), null, new BigDecimal("149.00"));

        assertEquals(new BigDecimal("0.00"), plan.surcharge());
        assertEquals(new BigDecimal("50.00"), plan.differenceOwed());
    }

    @Test
    @DisplayName("a zero or negative retry is refused before anything is written")
    void nonPositiveRetry() {
        // ck_rech_amount CHECK (AMOUNT > 0) says the same thing in the database.
        assertThrows(IllegalArgumentException.class, () -> plan("199.00", "0.00", "0.00"));
        assertThrows(IllegalArgumentException.class, () -> plan("199.00", "0.00", "-10.00"));
    }

    @Test
    @DisplayName("a third decimal is a programming error, not something to round")
    void thirdDecimal() {
        assertThrows(IllegalArgumentException.class,
                () -> RetryPlan.of(new BigDecimal("199.00"), new BigDecimal("0.00"),
                        new BigDecimal("198.995")));
    }

    private static RetryPlan plan(String original, String surcharge, String retry) {
        return RetryPlan.of(new BigDecimal(original), new BigDecimal(surcharge),
                new BigDecimal(retry));
    }
}
