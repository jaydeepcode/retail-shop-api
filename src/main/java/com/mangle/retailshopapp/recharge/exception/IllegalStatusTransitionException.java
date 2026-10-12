package com.mangle.retailshopapp.recharge.exception;

import java.util.Map;

import com.mangle.retailshopapp.recharge.domain.RechargeStatus;

/**
 * 409 — that move is not legal from the current status (design-domain.md §5.4).
 *
 * <p>Carries both statuses so the counter can say which one it found, which
 * matters when two staff are working the same worklist and one has already
 * completed the recharge the other is looking at.
 */
public class IllegalStatusTransitionException extends RechargeConflictException {

    public IllegalStatusTransitionException(RechargeStatus from, RechargeStatus to) {
        super(ErrorCode.ILLEGAL_STATUS_TRANSITION,
                "A recharge cannot go from %s to %s.".formatted(from, to),
                Map.of("fromStatus", from.name(), "toStatus", to.name()));
    }
}
