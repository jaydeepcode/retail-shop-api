package com.mangle.retailshopapp.recharge.model.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.mangle.retailshopapp.recharge.service.CounterEntryService.NumberHint;

/**
 * Operator and route prefill from a number's last recharge (§3.2 endpoint #2).
 *
 * <p>⚠ {@code known = false} WITH NULLS IS THE NORMAL ANSWER ON DAY ONE, not an
 * error. It reads {@code rc_recharge}, which is empty until V15's backfill runs
 * on cutover day, so every number is unknown to begin with. §3.2 specifies 200
 * with {@code known:false} rather than a 404 for exactly this reason — the
 * commonest case must not look like a failure.
 */
public record NumberHintResponse(
        boolean known,
        String operatorCode,
        String routeCode,
        BigDecimal lastAmount,
        LocalDateTime lastRechargeAt,
        String dishtvNo) {

    public static NumberHintResponse from(NumberHint hint) {
        return new NumberHintResponse(hint.known(), hint.operatorCode(), hint.routeCode(),
                hint.lastAmount(), hint.lastRechargeAt(), hint.dishtvNo());
    }
}
