package com.mangle.retailshopapp.apierror;

/** 404 — no payment with that id. */
public class PaymentNotFound extends RechargeNotFoundException {

    public PaymentNotFound(long paymentId) {
        super(ErrorCode.PAYMENT_NOT_FOUND, "No payment " + paymentId + ".");
    }
}
