package com.mangle.retailshopapp.recharge.model.response;

/**
 * The 409 body when a walk-in's mobile is already on file (§3.2 endpoint #5).
 *
 * <p>⚠ IT CARRIES THE EXISTING {@code customerId}, which is the whole point. §3.2
 * specifies "409 (mobile already on file → body carries the existing
 * {@code customerId})" so the staff member can use the person who is already
 * there instead of creating a duplicate someone has to merge later. A bare 409
 * would leave them stuck at the counter with a customer in front of them.
 */
public record CreateCustomerRequestResult(
        int existingCustomerId,
        String name,
        String contactNumber) {
}
