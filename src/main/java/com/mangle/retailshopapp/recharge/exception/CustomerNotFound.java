package com.mangle.retailshopapp.recharge.exception;

/**
 * 404 — no usable credit customer with that id.
 *
 * <p>Also raised for a LEDGER ACCOUNT, not only for a missing row: the three bank
 * pseudo-customers (269, 323, 409) are in {@code rs_cust_dtls} but are not people
 * and must never carry a recharge credit (log §2.6). From the counter's point of
 * view "that is not a customer" is the same answer either way.
 */
public class CustomerNotFound extends RechargeNotFoundException {

    public CustomerNotFound(long customerId) {
        super(ErrorCode.CUSTOMER_NOT_FOUND, "No credit customer " + customerId + ".");
    }

    /**
     * The row exists but is a ledger account rather than a person.
     *
     * <p>A separate factory rather than a second constructor so the call site
     * says which case it found — the id is real either way, and a reader of
     * {@code new CustomerNotFound(269)} would reasonably assume the row is
     * missing.
     */
    public static CustomerNotFound ledgerAccount(long customerId) {
        return new CustomerNotFound(customerId,
                "Customer %d is a ledger account, not a person; it cannot carry credit."
                        .formatted(customerId));
    }

    private CustomerNotFound(long customerId, String message) {
        super(ErrorCode.CUSTOMER_NOT_FOUND, message);
    }
}
