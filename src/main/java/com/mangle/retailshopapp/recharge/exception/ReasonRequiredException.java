package com.mangle.retailshopapp.recharge.exception;

/**
 * 422 — voiding needs a reason, and an empty string is not one (log §7.1).
 *
 * <p>The mandatory reason plus float reconciliation is the ONLY control against
 * using a void to refund a recharge that actually succeeded, which would restore
 * float that never came back. There is no second approver, because FRD §3 fixes
 * v1 as single-user — so the reason is not paperwork, it is the control.
 */
public class ReasonRequiredException extends RechargeRuleViolationException {

    public ReasonRequiredException(String action) {
        super(ErrorCode.REASON_REQUIRED, "A reason is required to " + action + ".");
    }
}
