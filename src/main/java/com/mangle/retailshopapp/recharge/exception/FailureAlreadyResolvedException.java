package com.mangle.retailshopapp.recharge.exception;

import java.util.Map;

import com.mangle.retailshopapp.recharge.domain.FailureResolution;

/** 409 — this failure has already been retried or refunded. */
public class FailureAlreadyResolvedException extends RechargeConflictException {

    public FailureAlreadyResolvedException(int rechargeId, FailureResolution resolution) {
        super(ErrorCode.FAILURE_ALREADY_RESOLVED,
                "Recharge %d was already %s.".formatted(rechargeId, resolution.name().toLowerCase()),
                Map.of("rechargeId", rechargeId, "resolution", resolution.name()));
    }
}
