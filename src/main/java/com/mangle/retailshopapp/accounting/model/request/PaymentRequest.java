package com.mangle.retailshopapp.accounting.model.request;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * An expense (endpoint 7, design-ledger.md E19): pick a category and a source
 * account (FRD §6).
 *
 * <p>Two legs: {@code Dr <expenseAccountCode> / Cr <sourceAccountCode>}, as a
 * {@code PAYMENT}.
 *
 * <p>⚠ <b>E19 PAYS FROM THE SAFE AND NOT FROM THE TILL</b> (log §3) — but that is
 * a fact about where the shop keeps its money, not a rule this endpoint enforces.
 * The expense account carries the business ({@code EXP_ELEC_WATER} is Water's
 * electricity however it was paid), and the source is whichever account the money
 * actually left. §1.3 gives no 422 list for #7; the one applied mirrors #8's,
 * which was itself derived while drawing the screen: an {@code ASSET} in
 * {@code REPORT_GROUP = 'CASH_AND_BANK'} with {@code ALLOW_MANUAL = TRUE}. That
 * admits the four banks, the safe and the three tills, and keeps
 * {@code UPI_CLEARING} and the floats out (A7).
 *
 * <p>⚠ AN {@code Idempotency-Key} HEADER IS REQUIRED, unlike #2 and #4. <b>Two
 * identical ₹1,200 electricity payments dated the same day may be one
 * double-click or two real bills</b>, and nothing in this payload separates them
 * — so only the client knows whether it is retrying (§1.3, "Idempotency,
 * endpoint by endpoint"). A close can derive its own key because "close POOL-1
 * for 9 Nov" is true at most once; an expense has no such natural key.
 *
 * @param voucherDate         the accounting date the money left. Not in the
 *                            future: a voucher dated next Friday is money the
 *                            shop has not spent
 * @param expenseAccountCode  an {@code EXPENSE} account with
 *                            {@code ALLOW_MANUAL = TRUE}. ⚠ {@code EXP_CASH_SHORT}
 *                            is excluded by that flag and must be: it is posted
 *                            by the close itself, for the genuinely unexplained
 *                            (log §9.1)
 * @param sourceAccountCode   where the money came from
 * @param narration           what it was for. The only record of that, so it is
 *                            required rather than optional — "₹1,200" with no
 *                            sentence is unreadable six months later
 */
public record PaymentRequest(
        @NotNull(message = "voucherDate is required")
        LocalDate voucherDate,

        @NotBlank(message = "expenseAccountCode is required: an expense needs a category")
        @Size(max = 30, message = "an account code is at most 30 characters")
        String expenseAccountCode,

        @NotBlank(message = "sourceAccountCode is required: name the account the money left")
        @Size(max = 30, message = "an account code is at most 30 characters")
        String sourceAccountCode,

        @NotNull(message = "amount is required")
        BigDecimal amount,

        @NotBlank(message = "narration is required: it is the only record of what this was for")
        @Size(max = 255, message = "a narration is at most 255 characters")
        String narration) {
}
