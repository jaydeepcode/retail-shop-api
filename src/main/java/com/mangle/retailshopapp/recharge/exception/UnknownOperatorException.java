package com.mangle.retailshopapp.recharge.exception;

/**
 * 404 — no live operator with that code.
 *
 * <p>Includes the retired ones, which is the point: {@code IDEA} and {@code VDFN}
 * were merged into {@code VI} by V20 (log §15.2), so a client still offering them
 * gets a clear refusal rather than a sale against a dead float wallet.
 */
public class UnknownOperatorException extends RechargeNotFoundException {

    public UnknownOperatorException(String operatorCode) {
        super(ErrorCode.UNKNOWN_OPERATOR, "No live operator with code " + operatorCode + ".");
    }
}
