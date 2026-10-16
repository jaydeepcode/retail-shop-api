package com.mangle.retailshopapp.recharge.model.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.mangle.retailshopapp.recharge.service.CreditLedgerService.CreditBookRow;

/**
 * One debtor on the credit book (design-api.md §3.2 endpoint #14).
 *
 * <p>⚠ {@code outstanding} AND {@code advanceHeld} ARE BOTH POSITIVE, and only
 * one of them is ever non-zero. The stored tally is single-sided and signed —
 * {@code DBT} negative, {@code DPAM} positive — and {@code Outstanding} is the
 * one place that convention becomes a number a screen can show (§1.8). A client
 * that had to reason about the sign of {@code req_amt} would be the second place,
 * and the two would eventually disagree.
 *
 * <p>The tab is ONE netted position shown as "owes ₹120" or "has ₹80 with us",
 * never as two numbers (log §2.8:180) — which is why both fields are here but
 * only one is ever populated.
 *
 * <p>⚠ {@code warn} IS COMPUTED SERVER-SIDE from the 30-day rule and
 * {@code rc_setting['credit.warning.days']}, not left to the client (§3.3). Two
 * clients applying their own threshold is how the same customer comes to be
 * flagged on one terminal and not on another.
 */
public record CreditBookRowResponse(
        int customerId,
        String name,
        String contactNumber,
        BigDecimal outstanding,
        BigDecimal advanceHeld,
        Integer oldestDebtDays,
        boolean warn,
        LocalDateTime lastActivityAt) {

    public static CreditBookRowResponse from(CreditBookRow row, LocalDate today, int warnDays) {
        return new CreditBookRowResponse(
                row.customerId(),
                row.name(),
                row.contactNumber(),
                row.position().owed(),
                row.position().advanceHeld(),
                row.position().ageInDays(today),
                row.position().warn(today, warnDays),
                row.lastActivityAt());
    }
}
