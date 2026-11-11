package com.mangle.retailshopapp.apierror;

/** 404 — no recharge with that id. */
public class RechargeNotFound extends RechargeNotFoundException {

    public RechargeNotFound(int rechargeId) {
        super(ErrorCode.RECHARGE_NOT_FOUND, "No recharge " + rechargeId + ".");
    }
}
