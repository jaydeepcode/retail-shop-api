package com.mangle.retailshopapp.recharge.model.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.mangle.retailshopapp.recharge.domain.ServiceType;

/**
 * One row of the worklist (design-api.md §3.2 endpoints #8 and #11).
 *
 * <p>⚠ THE WORKLIST IS A LEFTOVER TRAY, NOT A QUEUE (log §14.10). Measured: 468
 * of 681 active hours held exactly one recharge, the busiest hour in six months
 * held 7, and 89% of recharges have more than five minutes before the next. It is
 * normally EMPTY, so the screen should read as "nothing waiting" rather than as a
 * list that happens to have no rows.
 */
public record RechargeWorkItemResponse(
        int rechargeId,
        String reference,
        ServiceType serviceType,
        String number,
        String operatorCode,
        String operatorName,
        String routeCode,
        BigDecimal amount,
        BigDecimal surcharge,
        LocalDateTime rechargeAt,
        int paymentId,
        String failureReason) {
}
