package com.mangle.retailshopapp.recharge.exception;

/** 404 — something the request named does not exist. */
public class RechargeNotFoundException extends RechargeException {

    public RechargeNotFoundException(ErrorCode code, String message) {
        super(code, 404, message);
    }
}
