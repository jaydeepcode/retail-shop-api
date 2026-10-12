package com.mangle.retailshopapp.recharge.exception;

import java.util.Map;

/**
 * 409 — the request is well formed but the world has moved.
 *
 * <p>The distinction from 422 is worth keeping sharp: a 409 means "try again
 * after looking", a 422 means "this can never be right". A status transition that
 * is illegal from the CURRENT state is a 409 because the state may change; lines
 * that do not sum are a 422 because they never will.
 */
public class RechargeConflictException extends RechargeException {

    public RechargeConflictException(ErrorCode code, String message) {
        super(code, 409, message);
    }

    public RechargeConflictException(ErrorCode code, String message, Map<String, Object> details) {
        super(code, 409, message, details);
    }
}
