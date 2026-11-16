package com.mangle.retailshopapp.accounting.model.request;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Where the difference went, when somebody can say.
 *
 * <p>⚠ IT WORKS IN BOTH DIRECTIONS, AND THAT WAS A CORRECTION RATHER THAN AN
 * ADDITION (log §14.4, design-ledger-api.md §1.1). The block previously carried
 * only {@code expenseAccountCode}, validated as an {@code EXPENSE} account — so a
 * drawer that was OVER for a known reason could only ever be recorded as
 * unexplained, and two recurring cases arrive exactly that way: someone paid for
 * a photocopy (1,179 baskets over seven years) and small change rounded away (₹1
 * occurs 12,026 times, about ₹1,000 a year). Log §9.1 reasoned entirely about
 * shortfalls, but the principle it established — <b>a known cause posts as the
 * real thing rather than into the variance account</b> — applies identically to
 * money arriving. It was simply never written down that way.
 *
 * <p>{@code EXP_CASH_SHORT} and {@code INC_CASH_OVER} keep catching only the
 * genuinely unexplained, always and with no threshold. There is no "small enough
 * to ignore".
 *
 * @param direction     {@code OUT} = money left the drawer, {@code IN} = money arrived
 * @param accountCode   the real account to post against: an {@code EXPENSE} with
 *                      {@code ALLOW_MANUAL = TRUE} when {@code OUT}, an
 *                      {@code INCOME} with {@code ALLOW_MANUAL = TRUE} when
 *                      {@code IN}. Omitted when {@code reason} is given.
 * @param reason        a named posting whose accounts are fixed, so no account
 *                      code is typed. {@code RATE_CORRECTION} is the only one,
 *                      and only on a float through endpoint 4 — see
 *                      {@link ReconciliationRequest}.
 * @param amount        must equal the computed variance exactly; a partial
 *                      explanation is refused rather than split
 */
public record ExplainedVariance(
        @NotNull(message = "an explained variance needs a direction: IN or OUT")
        VarianceDirection direction,

        @Size(max = 30, message = "an account code is at most 30 characters")
        String accountCode,

        VarianceReason reason,

        @NotNull(message = "an explained variance needs an amount")
        BigDecimal amount) {

    /**
     * ⚠ The names are the wire values, so they are read by the screen's four
     * buttons (artboard 9) and are as much a contract as an {@code ErrorCode}.
     */
    public enum VarianceDirection {
        /** Money left the drawer — the shortfall case, log §9.1's original. */
        OUT,
        /** Money arrived — the photocopy and the rounding cases, log §14.3, §14.5. */
        IN
    }

    /**
     * A variance whose posting is named rather than typed.
     *
     * <p>⚠ {@code RATE_CORRECTION} exists because the account it credits is
     * {@code INC_RCHG_COMM}, which is {@code ALLOW_MANUAL = FALSE} (V21:510) and
     * so could never be named through {@code accountCode} — §1.1's own 422 list
     * refuses an {@code IN} account that is not {@code ALLOW_MANUAL = TRUE}. That
     * is not an awkwardness to work around; it is A7 working. The float is
     * machine-owned and is moved only by the endpoints that own it, and this
     * correction is one of them (log §14.9).
     */
    public enum VarianceReason {
        /**
         * A float's provisional rate replaced by the observed one: one
         * {@code JOURNAL}, {@code Dr FLOAT_X / Cr INC_RCHG_COMM}, posted in the
         * same transaction as the {@code acc_reconciliation} row, so a float
         * balance can never move without a record of the check that prompted it
         * (log §8.4:709-714, §14.9).
         *
         * <p>That single journal repairs the commission understatement and the
         * float drift together, <b>because both errors are the same rupee
         * amount</b> — which is itself the check that the correction is right.
         */
        RATE_CORRECTION
    }

    public boolean isIn() {
        return direction == VarianceDirection.IN;
    }
}
