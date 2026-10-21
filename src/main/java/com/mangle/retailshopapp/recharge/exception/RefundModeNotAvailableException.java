package com.mangle.retailshopapp.recharge.exception;

import java.util.Map;

import com.mangle.retailshopapp.recharge.domain.ResidualMode;

/**
 * 422 — money cannot leave {@code RCHG_PENDING} that way for this sale.
 *
 * <p>⚠ THE AVAILABLE MODES ARE A FACT ABOUT THE ORIGINAL PAYMENT, not a choice
 * (log §7.4). "On a credit original no cash was ever collected, so there is no
 * choice to offer: it can only reduce the tab." The mirror holds too — a
 * {@code CREDIT_REDUCTION} against a payment that had no credit line would
 * reduce a debt the sale never created, which design-api.md:789 states as the
 * rule this exception enforces: "credit reduction only where credit was
 * extended, and only for that line's customer".
 *
 * <p>Since {@code KEEP_AS_ADVANCE} was withdrawn on 2026-10-21 every mode is
 * conditional, so {@code available} is derived entirely from the payment's tender
 * lines. It cannot come back empty: V12's header invariant makes the lines sum to
 * the total, so a settled payment carries at least one, and each of the three
 * line types enables exactly one mode.
 *
 * <p>Lists what IS available in {@code details}, because a counter that is told
 * only "no" has to guess, and the answer is already known here.
 */
public class RefundModeNotAvailableException extends RechargeRuleViolationException {

    public RefundModeNotAvailableException(ResidualMode requested, String why,
                                           java.util.List<ResidualMode> available) {
        super(ErrorCode.REFUND_MODE_NOT_AVAILABLE,
                "%s is not available here: %s".formatted(requested.name(), why),
                Map.of("requested", requested.name(),
                        "available", available.stream().map(Enum::name).toList()));
    }

    /**
     * A mode that needs a named customer was asked for without one.
     *
     * <p>⚠ A BACKSTOP RATHER THAN A LIVE PATH since 2026-10-21.
     * {@code CREDIT_REDUCTION} is the only mode left with
     * {@code needsCustomer()}, and its customer is read from the payment's credit
     * line, where {@code rc_credit_req.CUST_ID} is {@code NOT NULL} — so nothing
     * can reach this today. It is kept because it is the check that stops a
     * {@code REQUIRES_PARTY} leg becoming a posting failure instead of a 422, and
     * a future mode would need it again.
     */
    public static RefundModeNotAvailableException needsCustomer(
            ResidualMode requested, java.util.List<ResidualMode> available) {
        return new RefundModeNotAvailableException(requested,
                "it must name the customer whose account the money goes to.",
                available);
    }
}
