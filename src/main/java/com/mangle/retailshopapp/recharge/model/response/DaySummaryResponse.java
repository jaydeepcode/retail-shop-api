package com.mangle.retailshopapp.recharge.model.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * End of day (§3.2 endpoint #18).
 *
 * <p>⚠ THIS IS WHAT THE SLICE'S GATE COMPARES TO THE DRAWER
 * (implementation-plan.md:125).
 *
 * <p><b>⚠ COMPARE THE DRAWER AGAINST {@code totalCollected}, NOT
 * {@code totalTakings}.</b>
 *
 * <pre>
 * drawer  =  totalCollected  +  heldPending        where totalCollected = takings + surcharge
 * </pre>
 *
 * <p>{@code totalTakings} is the recharge FACE VALUE and excludes the shop's own
 * charge, which is its own income line by decision (log §10.2, §15.11). A screen
 * comparing the drawer against takings alone shows a phantom shortfall of ₹10 per
 * DTH recharge. {@code totalCollected} exists so that cannot happen.
 *
 * <p>{@code heldPending} is the figure that explains the gap, and it comes from
 * the {@code RCHG_PENDING} account balance rather than from a status join (log
 * §7.2) — so it is reconcilable rather than merely displayed. The screen should
 * say "₹X held against N unresolved recharges" and NOT "till mismatch": cash in
 * the drawer not equalling takings is correct whenever a recharge has failed and
 * not been refunded, because the money is held.
 *
 * <p>There is deliberately <b>no day-close action</b> (§3.2). Requirement 8 asks
 * for a warning, never a block, which is a read — introducing a day-close
 * resource would create closed/open day state, a reopen endpoint and a rule about
 * posting into a closed day, none of which is asked for.
 */
public record DaySummaryResponse(
        LocalDate date,
        List<TakingsRow> takings,
        BigDecimal totalTakings,
        BigDecimal totalSurcharge,
        BigDecimal totalCollected,
        Map<String, BigDecimal> tenderMix,
        long awaitingCount,
        long unresolvedFailureCount,
        BigDecimal heldPending) {

    /** Takings for one operator-and-route pair, COMPLETED only. */
    public record TakingsRow(
            String operatorCode,
            String routeCode,
            long count,
            BigDecimal amount,
            BigDecimal surcharge) {
    }
}
