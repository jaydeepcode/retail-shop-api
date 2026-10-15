package com.mangle.retailshopapp.recharge.model.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Settle one basket (design-api.md §3.3).
 *
 * <p>⚠ NOTE WHAT IS ABSENT: no total, no change, no status, and no
 * {@code routeCode}. All four are server-owned. The total is recomputed from the
 * recharges so the client cannot disagree with it; the route is defaulted from
 * the operator's configuration and confirmed at completion, because at the moment
 * the customer pays nobody yet knows which connection will be used (log §14.6).
 *
 * <p>{@code @Size(max = 20)} on recharges and {@code max = 5} on lines are
 * §6.2's, and generous rather than tight: the measured maximum basket holds three
 * recharges (log §14.10) and three tenders is the most the counter offers. The
 * limits exist to bound a malformed or hostile request, not to express a rule.
 */
public record SettleBasketRequest(

        @NotEmpty(message = "a basket needs at least one recharge")
        @Size(max = 20, message = "a basket takes at most 20 recharges")
        @Valid
        List<RechargeLineRequest> recharges,

        @NotEmpty(message = "a basket needs at least one payment line")
        @Size(max = 5, message = "a payment takes at most 5 lines")
        @Valid
        List<SettlementLineRequest> settlementLines) {
}
