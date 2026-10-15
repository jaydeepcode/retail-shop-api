package com.mangle.retailshopapp.recharge.exception;

import java.util.Map;

/**
 * 422 — the operator has no open commission rate, so the INCOME post is refused
 * (log §8.2).
 *
 * <p>⚠ THE SALE STILL STANDS, and that is why this is safe. Under log §7.2 the
 * rate lookup runs at the income post, not the cash post: by the time this
 * throws, {@code Dr CASH_RECHARGE / Cr RCHG_PENDING} is already in the ledger and
 * the customer has paid. Refusing here delays a bookkeeping entry, not a sale,
 * and the amount sits in {@code RCHG_PENDING} looking exactly like an unresolved
 * recharge — which is the balance §7.2 built for open exposure.
 *
 * <p>{@code r = 0} was the alternative and is NOT a neutral default
 * (overturning design-ledger.md:800-804): {@code r} also sets the float
 * decrement, so a zero records the portal debiting ₹199 on a ₹199 sale when it
 * really debited ₹193.23. Commission is understated AND
 * {@code SUM(FACE_DELTA)} drifts from the portal by the same amount, breaking the
 * one independent check the design has on float.
 */
public class NoCommissionRateException extends RechargeRuleViolationException {

    public NoCommissionRateException(String operatorName, String operatorCode, String routeCode) {
        super(ErrorCode.NO_COMMISSION_RATE,
                "%s has no commission rate on file — add one to complete."
                        .formatted(operatorName),
                Map.of("operatorCode", operatorCode, "routeCode", routeCode));
    }
}
