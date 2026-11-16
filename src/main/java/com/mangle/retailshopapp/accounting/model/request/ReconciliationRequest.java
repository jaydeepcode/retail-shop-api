package com.mangle.retailshopapp.accounting.model.request;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Closing a single reconcilable account that is not in a pool (endpoint 4):
 * {@code CASH_SAFE}, a bank, a float.
 *
 * <p><b>No sweep.</b> There is no account above the safe to sweep into, which is
 * half of why the safe closes here rather than as a pool of one — see
 * {@code adr/0002-cash-safe-closes-as-an-account-not-a-pool.md}.
 *
 * <p>⚠ <b>ON A FLOAT THIS IS ALSO THE ONLY PLACE ITS BALANCE MAY BE
 * CORRECTED</b>, never endpoint 9 (log §14.9). Two documents contradicted each
 * other on whether a float could be corrected at all: A7 makes the float
 * accounts {@code ALLOW_MANUAL = FALSE}, while log §8.4:709-710 requires a
 * {@code JOURNAL} moving {@code Dr FLOAT_X / Cr INC_RCHG_COMM} once a provisional
 * rate is replaced by the observed one. As the two stood, a float could be
 * neither topped up nor adjusted. Routing the correction through here keeps both
 * intents: nobody hand-posts a float through the generic journal endpoint, and
 * the correction is written in the same transaction as the
 * {@code acc_reconciliation} row.
 *
 * <p>⚠ NO {@code Idempotency-Key} HEADER. The key is
 * {@code ACCT_RECON:<accountCode>:<reconDate>}, server-derived, with
 * {@code uk_recon_account_date} as the guard — the same reasoning as
 * {@link PoolCloseRequest}.
 *
 * @param reconDate     the business day being sealed; required, never today by default
 * @param countedAmount the independent source: a cash count, a bank statement, a
 *                      portal balance. The COST side on a float.
 * @param faceCounted   float accounts only: the portal's FACE balance, written to
 *                      {@code FACE_COUNTED} against {@code SUM(FACE_DELTA)} in
 *                      {@code FACE_LEDGER} (§3, V22:162-164).
 *                      <b>Optional, and defaults to {@code countedAmount}</b> —
 *                      all four of the shop's wallets are FRD §7.2 pattern 2 with
 *                      a cost/face ratio of exactly 1.0 (log §2.4:97-99), so the
 *                      one figure read off the portal is both. The field exists
 *                      so a wallet whose ratio stops being 1.0 is expressible
 *                      without a schema or contract change; the portal-versus-
 *                      books comparison is the only independent check the design
 *                      has on float and it must not quietly become a comparison
 *                      of a number with itself.
 * @param note          FRD §8.1's "a variance with a note"
 * @param explained     optional; takes the same block as endpoint 2, plus the one
 *                      float-specific {@code reason}, {@code RATE_CORRECTION}
 */
public record ReconciliationRequest(
        @NotNull(message = "reconDate is required: a close has to name the day it seals")
        LocalDate reconDate,

        @NotNull(message = "countedAmount is required")
        BigDecimal countedAmount,

        BigDecimal faceCounted,

        @Size(max = 255, message = "a note is at most 255 characters")
        String note,

        @Valid ExplainedVariance explained) {
}
