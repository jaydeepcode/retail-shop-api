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

    /** A mode that needs a named customer was asked for without one. */
    public static RefundModeNotAvailableException needsCustomer(ResidualMode requested) {
        return new RefundModeNotAvailableException(requested,
                "it must name the customer whose account the money goes to.",
                java.util.List.of(ResidualMode.CASH));
    }
}
