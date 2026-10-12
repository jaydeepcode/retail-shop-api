package com.mangle.retailshopapp.recharge.exception;

import java.util.Map;

/** 422 — a business rule refuses this, and no retry will change that. */
public class RechargeRuleViolationException extends RechargeException {

    public RechargeRuleViolationException(ErrorCode code, String message) {
        super(code, 422, message);
    }

    public RechargeRuleViolationException(ErrorCode code, String message,
                                          Map<String, Object> details) {
        super(code, 422, message, details);
    }
}
