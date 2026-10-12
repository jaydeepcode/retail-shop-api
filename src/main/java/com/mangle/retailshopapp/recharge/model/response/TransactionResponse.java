package com.mangle.retailshopapp.recharge.model.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The whole visit (design-api.md §3.3, §3.2 endpoints #6 and #7).
 *
 * <p>⚠ {@code changeDue} IS REPORTED EVEN WHEN IT IS NOT DISPLAYED, and
 * {@code changeDisplayed} on the cash line is what says which. log §14.5: change
 * below ₹5 is recorded but not shown, because a rupee is not actually handed over
 * — there is no coin and the customer waves it away. The figure stays on the wire
 * so the evening close can account for the roughly ₹1,000 a year that arrives as
 * cash over, which §14.4's explain box is what names.
 */
public record TransactionResponse(
        int paymentId,
        String paymentReference,
        LocalDateTime takenAt,
        String staff,
        String financialYear,
        BigDecimal totalDue,
        BigDecimal totalSurcharge,
        BigDecimal cashTendered,
        BigDecimal changeDue,
        List<RechargeResponse> recharges,
        List<SettlementLineResponse> settlementLines,
        CreditSummaryResponse credit) {
}
