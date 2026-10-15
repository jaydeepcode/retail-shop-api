package com.mangle.retailshopapp.recharge.exception;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 422 — the settlement lines do not add up to the total the server computed.
 *
 * <p>Carries BOTH totals in {@code details}, per §8.4, so the counter can show
 * the shortfall rather than just refusing. The expected figure is the one
 * recomputed from the recharges — the client never sends a total, so it cannot
 * disagree with one (§3.3).
 */
public class LinesDoNotSumException extends RechargeRuleViolationException {

    public LinesDoNotSumException(BigDecimal expectedTotal, BigDecimal providedTotal) {
        super(ErrorCode.LINES_DO_NOT_SUM,
                "The payment lines come to %s but the basket totals %s."
                        .formatted(providedTotal.toPlainString(), expectedTotal.toPlainString()),
                Map.of("expectedTotal", expectedTotal, "providedTotal", providedTotal));
    }
}
