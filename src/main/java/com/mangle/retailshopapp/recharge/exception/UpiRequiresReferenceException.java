package com.mangle.retailshopapp.recharge.exception;

/**
 * 422 — a UPI refund with no transaction reference in the note.
 *
 * <p>The mirror of {@link CreditRequiresCustomerException}, and for the same kind
 * of reason: a mode whose account cannot be read later without the one field that
 * identifies the movement. {@code UPI_CLEARING} is {@code IS_RECONCILABLE}
 * (V21:291), so the outgoing amount surfaces when UPI is reconciled against the
 * bank — and the reference is the only thing that ties that line to the actual
 * transfer. A blank one is discovered at reconciliation time, when the transfer
 * can no longer be reconstructed.
 *
 * <p>⚠ A SEPARATE CODE RATHER THAN {@code REFUND_MODE_NOT_AVAILABLE}, because
 * §8.4's reason for stable codes is that a keyboard-driven counter has to decide
 * WHERE TO PUT THE CURSOR. The mode IS available here; the note field is what is
 * wrong, and the two answers send the cursor to different places.
 */
public class UpiRequiresReferenceException extends RechargeRuleViolationException {

    public UpiRequiresReferenceException() {
        super(ErrorCode.UPI_REQUIRES_REFERENCE,
                "A UPI refund needs the UPI transaction reference in the note.");
    }
}
