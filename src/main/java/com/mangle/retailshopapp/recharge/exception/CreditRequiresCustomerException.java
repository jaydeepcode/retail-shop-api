package com.mangle.retailshopapp.recharge.exception;

/**
 * 422 — a credit line with no identified customer.
 *
 * <p>Thrown from TWO layers on purpose (§5.3): {@code SettlementPlan} catches the
 * structural case and gives a good message, and {@code CreditLedgerService}
 * enforces it again at the lowest level so no future caller can bypass it. The
 * second check is the one that makes the rule unbypassable; the first is the one
 * that makes it pleasant.
 */
public class CreditRequiresCustomerException extends RechargeRuleViolationException {

    public CreditRequiresCustomerException() {
        super(ErrorCode.CREDIT_REQUIRES_CUSTOMER, "A credit line needs an identified customer.");
    }

    public CreditRequiresCustomerException(String message) {
        super(ErrorCode.CREDIT_REQUIRES_CUSTOMER, message);
    }
}
