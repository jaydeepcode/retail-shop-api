package com.mangle.retailshopapp.accounting.model.request;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The day close (endpoint 2, A1): one POST, not a count plus a separate sweep.
 *
 * <p>⚠ {@code countedAmount} IS ONE FIGURE FOR THE WHOLE DRAWER, because
 * counting is per physical drawer and the drawer does not follow the business
 * lines — {@code CASH_RECHARGE} and {@code CASH_TICKET} share one (log §3). The
 * service apportions it across the members and lands the whole variance on the
 * {@code IS_POOL_PRIMARY} one (A2, E17d).
 *
 * <p>⚠ NO {@code Idempotency-Key} HEADER, and that is a deliberate exception to
 * design-api.md §1.1's convention (design-ledger-api.md §1.3, "Idempotency,
 * endpoint by endpoint"). "Close POOL-1 for 9 Nov" is true at most once, so the
 * server can derive the key — {@code POOL_CLOSE:<poolCode>:<reconDate>} — and
 * {@code uk_recon_account_date} already enforces it. A required header whose
 * value the server ignores is worse than none, because it tells a client the
 * value matters and someone will send a fresh one per click.
 *
 * @param reconDate     the BUSINESS day being sealed. <b>Required, and never
 *                      defaulted server-side to today</b> — see
 *                      {@code AccReconciliation#getReconDate()}. The screen
 *                      defaults it to the oldest unclosed day.
 * @param countedAmount what was physically counted, for the whole drawer
 * @param note          FRD §8.1's "a variance with a note"
 * @param explained     optional; omit when nobody can say where it went
 */
public record PoolCloseRequest(
        @NotNull(message = "reconDate is required: a close has to name the day it seals")
        LocalDate reconDate,

        @NotNull(message = "countedAmount is required")
        BigDecimal countedAmount,

        @Size(max = 255, message = "a note is at most 255 characters")
        String note,

        @Valid ExplainedVariance explained) {
}
