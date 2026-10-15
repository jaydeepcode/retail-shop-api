package com.mangle.retailshopapp.recharge.model.response;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.mangle.retailshopapp.recharge.domain.Outstanding;

/**
 * The credit block on a settle response (design-api.md §3.3).
 *
 * <p>⚠ {@code outstanding} IS ALWAYS A POSITIVE AMOUNT OWED, and
 * {@code advanceHeld} is its counterpart — the two are kept apart rather than
 * netted into one signed number, because log §2.8 shows the tab as "owes ₹120" or
 * "has ₹80 with us" and never as a minus sign. §14.2 records what collapsing them
 * costs: the same ₹52 counted twice in opposite directions inside a sealed
 * opening journal.
 *
 * <p>{@code warn} is computed server-side from the 30-day rule so two clients
 * cannot disagree about it (§3.3). Expect it true on 33 of 34 existing balances —
 * §7.1(b) says the client should see that before the red badge ships.
 */
public record CreditSummaryResponse(
        Integer customerId,
        String customerName,
        BigDecimal amountExtended,
        BigDecimal outstandingAfter,
        BigDecimal advanceHeld,
        Integer oldestDebtDays,
        boolean warn) {

    public static CreditSummaryResponse from(Integer customerId, String customerName,
                                             BigDecimal amountExtended, Outstanding position,
                                             LocalDate today, int warnDays) {
        return new CreditSummaryResponse(
                customerId,
                customerName,
                amountExtended,
                position.owed(),
                position.advanceHeld(),
                position.ageInDays(today),
                position.warn(today, warnDays));
    }
}
