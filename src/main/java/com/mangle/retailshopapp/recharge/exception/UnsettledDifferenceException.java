package com.mangle.retailshopapp.recharge.exception;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 422 — a retry changed the amount and the difference was not settled.
 *
 * <p>⚠ THE DIFFERENCE IS SETTLED IN THE SAME CALL, and §3.3 says why in one
 * line: "which is what keeps it in one transaction". A retry that recorded the
 * new recharge and left the money owed to a later request would be exactly the
 * partial state the settle path was built to make impossible — and the shop
 * would have no record that anything was owed at all.
 *
 * <p>Carries the amount in {@code details}, per §8.4, so the counter can offer
 * the figure rather than make the staff member work it out.
 */
public class UnsettledDifferenceException extends RechargeRuleViolationException {

    /** The customer is owed money back and no mode was given. */
    public static UnsettledDifferenceException owed(BigDecimal differenceOwed) {
        return new UnsettledDifferenceException(
                "This retry leaves %s owed back; say how it is settled."
                        .formatted(differenceOwed.toPlainString()),
                Map.of("differenceOwed", differenceOwed));
    }

    /** The retry costs more and no top-up was given. */
    public static UnsettledDifferenceException topUp(BigDecimal topUpDue) {
        return new UnsettledDifferenceException(
                "This retry costs %s more than was collected; say how it is taken."
                        .formatted(topUpDue.toPlainString()),
                Map.of("topUpDue", topUpDue));
    }

    /**
     * Something was sent to settle a difference that does not exist.
     *
     * <p>Refused rather than ignored. A client that sends a cash-back for a
     * same-amount retry has misunderstood the request, and silently dropping it
     * would mean the staff member believes money was handed over when the
     * drawer says otherwise.
     */
    public static UnsettledDifferenceException nothingToSettle(String what) {
        return new UnsettledDifferenceException(
                "This retry is for the same amount, so there is no %s to settle."
                        .formatted(what),
                Map.of("differenceOwed", BigDecimal.ZERO, "topUpDue", BigDecimal.ZERO));
    }

    /**
     * A refund was sent for something other than the whole line total.
     *
     * <p>⚠ A PARTIAL REFUND HAS NOWHERE TO GO. The amount held in
     * {@code RCHG_PENDING} for this line is {@code amount + surcharge}, and the
     * recharge moves to {@code REFUNDED}, which is terminal — so whatever was
     * not refunded would stay in a machine-owned account against a recharge
     * that can never be retried, completed or refunded again. §7.1(c)'s
     * open-exposure figure would then carry a balance nothing can clear.
     */
    public static UnsettledDifferenceException refundMustBeWhole(BigDecimal lineTotal,
                                                                 BigDecimal provided) {
        return new UnsettledDifferenceException(
                "A refund is for the whole line total of %s; got %s."
                        .formatted(lineTotal.toPlainString(), provided.toPlainString()),
                Map.of("expectedAmount", lineTotal, "providedAmount", provided));
    }

    private UnsettledDifferenceException(String message, Map<String, Object> details) {
        super(ErrorCode.UNSETTLED_DIFFERENCE, message, details);
    }
}
