package com.mangle.retailshopapp.recharge.model.response;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.mangle.retailshopapp.customer.model.CustomerDetails;
import com.mangle.retailshopapp.recharge.domain.Outstanding;

/**
 * A credit customer as the basket screen sees them (§3.2 endpoints #4 and #5).
 *
 * <p>⚠ IT CARRIES {@code outstanding} AND {@code warn} SO THE UI CAN WARN BEFORE
 * COMMIT (requirement 3). That is the entire reason the panel exists, and it is
 * also why the figures behind it are cross-business (log §14.8): scoped to
 * recharge, someone owing ₹2,000 on water and ₹49 on recharge would show as ₹49 —
 * understating the exposure while looking authoritative.
 *
 * <p>{@code advanceHeld} is its own field rather than a negative
 * {@code outstanding}, because log §2.8 shows the tab as "owes ₹120" or "has ₹80
 * with us" and never as a minus sign.
 */
public record CreditCustomerResponse(
        int customerId,
        String name,
        String contactNumber,
        BigDecimal outstanding,
        BigDecimal advanceHeld,
        Integer oldestDebtDays,
        boolean warn) {

    public static CreditCustomerResponse from(CustomerDetails c, Outstanding position,
                                              LocalDate today, int warnDays) {
        return new CreditCustomerResponse(
                c.getCustId(),
                c.getCustomerName(),
                c.getContactNum(),
                position.owed(),
                position.advanceHeld(),
                position.ageInDays(today),
                position.warn(today, warnDays));
    }
}
