package com.mangle.retailshopapp.recharge.exception;

import java.util.Map;

/** 400 — the request itself is malformed in a way bean validation did not catch. */
public class RechargeBadRequestException extends RechargeException {

    public RechargeBadRequestException(ErrorCode code, String message) {
        super(code, 400, message);
    }

    public RechargeBadRequestException(ErrorCode code, String message, Map<String, Object> details) {
        super(code, 400, message, details);
    }
}
