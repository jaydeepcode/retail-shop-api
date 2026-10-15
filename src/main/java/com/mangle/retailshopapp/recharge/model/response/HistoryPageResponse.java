package com.mangle.retailshopapp.recharge.model.response;

import java.time.LocalDate;
import java.util.List;

/**
 * A page of history, with the year it resolved to (§3.2 endpoint #17).
 *
 * <p>⚠ IT ECHOES THE RESOLVED YEAR AND ITS BOUNDS so the UI never computes
 * financial-year boundaries itself and the two cannot drift (§3.6). The label is
 * the wire form {@code "2026-27"}, which satisfies §6.2's own
 * {@code ^\d{4}-\d{2}$} pattern — so a response can be fed straight back as the
 * next request's parameter.
 *
 * <p>{@code availableYears} is empty until V15 runs, and that emptiness is the
 * signal the screen needs: history is GENUINELY blank on day one and fills as new
 * sales are taken (log §15.11). An empty list means "nothing recorded yet", which
 * is a different sentence from "no results for this filter".
 */
public record HistoryPageResponse(
        String financialYear,
        LocalDate startDate,
        LocalDate endDate,
        List<String> availableYears,
        PageResponse<RechargeResponse> page) {
}
