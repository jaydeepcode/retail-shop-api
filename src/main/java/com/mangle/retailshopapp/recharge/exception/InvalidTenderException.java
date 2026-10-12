package com.mangle.retailshopapp.recharge.exception;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 422 — the cash tendered is less than the cash line, or tender appears on a line
 * that is not cash.
 *
 * <p>A SHORT tender is not this error. §3.3: "a short or zero tender is expressed
 * by the client as a smaller CASH line plus a CREDIT line — the server does not
 * silently invent credit". This fires when the arithmetic of one cash line is
 * itself impossible.
 */
public class InvalidTenderException extends RechargeRuleViolationException {

    public InvalidTenderException(String message) {
        super(ErrorCode.INVALID_TENDER, message);
    }

    public InvalidTenderException(BigDecimal tendered, BigDecimal cashLineAmount) {
        super(ErrorCode.INVALID_TENDER,
                "Cash tendered (%s) is less than the cash line (%s)."
                        .formatted(tendered.toPlainString(), cashLineAmount.toPlainString()),
                Map.of("amountTendered", tendered, "cashLineAmount", cashLineAmount));
    }
}
