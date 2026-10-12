package com.mangle.retailshopapp.recharge.model.response;

import java.math.BigDecimal;

import com.mangle.retailshopapp.recharge.domain.SettlementMethod;

/**
 * One tender as the counter sees it (design-api.md §3.3).
 *
 * <p>{@code changeGiven} is DERIVED from the tender and the cash line, never sent
 * by the client. It appears on the cash line even though the schema holds the
 * tender on the header, because one drawer and one hand-over means there is only
 * ever one cash line to attach it to.
 */
public record SettlementLineResponse(
        int lineId,
        SettlementMethod method,
        BigDecimal amount,
        BigDecimal amountTendered,
        BigDecimal changeGiven,
        boolean changeDisplayed,
        String reference,
        Integer customerId,
        String customerName,
        String extendedBy,
        String note) {
}
