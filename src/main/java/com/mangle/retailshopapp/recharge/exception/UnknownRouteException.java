package com.mangle.retailshopapp.recharge.exception;

/** 404 — no live route with that code. */
public class UnknownRouteException extends RechargeNotFoundException {

    public UnknownRouteException(String routeCode) {
        super(ErrorCode.UNKNOWN_ROUTE, "No live route with code " + routeCode + ".");
    }
}
