package com.mangle.retailshopapp.recharge.model.response;

import java.math.BigDecimal;

import com.mangle.retailshopapp.recharge.domain.ResidualMode;

/**
 * What a refund did (design-api.md §3.2 #13).
 *
 * <p>{@code credit} is null on a cash refund and that is the normal case —
 * handing the cash back needs no customer at all, which is what keeps the
 * walk-in anonymous as FRD §8.7:316 requires.
 */
public record RefundResponse(
        RechargeResponse recharge,
        BigDecimal amount,
        ResidualMode mode,
        CreditSummaryResponse credit) {
}
