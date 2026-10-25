package com.mangle.retailshopapp.settlement;

import java.math.BigDecimal;

/**
 * One tender on a sale.
 *
 * <p>{@code amountTendered} is meaningful only on a {@code CASH} line and
 * {@code changeGiven} is derived from it, never sent by the client
 * (design-api.md §3.3).
 *
 * <p>Promoted out of {@code SettlementPlan} in S4: a ticket is settled with
 * exactly these lines against a different basket, so they are not recharge's.
 */
public record SettlementLine(
        SettlementMethod method,
        BigDecimal amount,
        BigDecimal amountTendered,
        String reference,
        Integer customerId,
        String note) {

    public SettlementLine {
        amount = Amounts.requireScale2(amount, "amount");
        amountTendered = amountTendered == null
                ? null : Amounts.requireScale2(amountTendered, "amountTendered");
    }
}
