package com.mangle.retailshopapp.recharge.model.response;

import java.math.BigDecimal;

import com.mangle.retailshopapp.recharge.domain.ResidualMode;
import com.mangle.retailshopapp.recharge.domain.SettlementMethod;

/**
 * What a retry did (design-api.md §3.3's worked example).
 *
 * <p>Both recharges are returned, not just the new one: the counter has to show
 * that the original moved to {@code FAILED/RETRIED} in the same breath as the
 * new attempt appearing, or the staff member cannot tell whether the failure is
 * still on the worklist.
 *
 * <p>{@code differenceOwed} and {@code topUpTaken} are both present and only one
 * can be non-zero. §3.3's example carries a signed-looking single field; two
 * named fields were chosen instead so a client never has to reason about the
 * sign of money, which is the mistake §1.8 records about {@code req_amt}.
 */
public record RetryRechargeResponse(
        RechargeResponse newRecharge,
        RechargeResponse originalRecharge,
        BigDecimal differenceOwed,
        ResidualMode differenceSettledAs,
        BigDecimal topUpTaken,
        SettlementMethod topUpTakenAs,
        CreditSummaryResponse credit) {
}
