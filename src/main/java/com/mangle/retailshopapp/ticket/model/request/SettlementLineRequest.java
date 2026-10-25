package com.mangle.retailshopapp.ticket.model.request;

import java.math.BigDecimal;

import com.mangle.retailshopapp.settlement.SettlementMethod;

import jakarta.validation.constraints.NotNull;

/**
 * One tender line, identical to {@code recharge}'s.
 *
 * <p>⚠ Declared again rather than imported from {@code recharge.model.request},
 * and that is the one duplication in this slice that is deliberate: a request
 * record is a module's wire contract, and sharing it would couple
 * {@code /tickets/*}'s API shape to {@code /recharge/*}'s. What IS shared is
 * everything behind it — {@code SettlementMethod}, {@code SettlementLine} and
 * every rule in {@code TenderPlan}. The rules are shared; the contract is each
 * endpoint's own.
 */
public record SettlementLineRequest(

        @NotNull(message = "a settlement method is required")
        SettlementMethod method,

        @NotNull(message = "an amount is required on every settlement line")
        BigDecimal amount,

        /** CASH only. Absent means they gave the exact money. */
        BigDecimal amountTendered,

        /** UPI only, and optional. */
        String reference,

        /** CREDIT only, and required for it (FRD §8.7). */
        Integer customerId,

        String note) {
}
