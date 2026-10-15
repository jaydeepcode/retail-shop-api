package com.mangle.retailshopapp.recharge.exception;

/**
 * 422 — more than one credit line on one payment (§3.3).
 *
 * <p>One tab per payment keeps {@code rc_txn_payment}'s {@code CRE_REQ_ID} a
 * single reference and keeps "who owes what for this visit" answerable without
 * splitting one sale across two people.
 */
public class TooManyCreditLinesException extends RechargeRuleViolationException {

    public TooManyCreditLinesException(int count) {
        super(ErrorCode.TOO_MANY_CREDIT_LINES,
                "A payment takes at most one credit line; this one has " + count + ".");
    }
}
