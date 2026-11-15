package com.mangle.retailshopapp.accounting.model.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Everything closeable tonight, in one call (endpoint 1).
 *
 * <p>⚠ <b>THE PATH HAS NO {@code {poolCode}} ON IT, AND THAT WAS SETTLED FROM
 * THE ARTBOARD RATHER THAN FROM THE DOCUMENT</b> (2026-11-02,
 * design-ledger-api.md §1.1). §1.1 named a {@code PoolClosePreviewResponse} and
 * never said what was in it; closing that omission against artboard 9 changed the
 * endpoint's shape instead of merely filling in its fields. The artboard is ONE
 * screen showing everything closeable — the recharge/ticket drawer in detail,
 * then "Also to close tonight" with the water drawer and the safe, then a
 * projected "After closing — Safe". So one call per pool would have been three
 * round trips for one screen.
 *
 * <p>⚠ {@code CASH_SAFE}'s balance is in here because of that projection, and
 * because the safe has no preview of its own: §1.1 has a pool preview, a pool
 * close, a close history and a single-account close, and no single-account
 * preview — and {@code /reports/balance-sheet} (endpoint 14) is outside this
 * slice.
 */
public record ClosePreviewResponse(List<PoolPreview> pools, List<AccountPreview> accounts) {

    /**
     * One cash pool and its members.
     *
     * @param expectedAmount the sum of the members' DERIVED balances, <b>never of
     *                       their last RECORDED ones</b>: the sweep leaves every
     *                       member at zero, so a recorded figure would read
     *                       {@code 0.00} every evening (§1.1)
     * @param lastClosedAt   {@code acc_reconciliation.CLOSED_AT} — when the drawer
     *                       was physically last emptied, which is the left edge of
     *                       {@code expectedAmount}. Without it that figure has no
     *                       period attached and one day's takings look like three.
     * @param lastReconDate  {@code RECON_DATE} — which business day was sealed.
     *                       The two agree on an ordinary evening and diverge
     *                       whenever a close happens after midnight, which is why
     *                       both are returned.
     * @param closeable      {@code false} with a {@code reason} is what renders
     *                       the water drawer's "CANNOT BALANCE YET": the server
     *                       states the fact, the screen owns the wording
     * @param reason         a display reason code, null when {@code closeable}.
     *                       ⚠ Deliberately NOT an {@code ErrorCode} — nothing here
     *                       is an error, and §8.4's contract is about the error
     *                       vocabulary.
     */
    public record PoolPreview(
            String poolCode,
            BigDecimal expectedAmount,
            LocalDateTime lastClosedAt,
            LocalDate lastReconDate,
            boolean closeable,
            String reason,
            List<MemberPreview> members) {
    }

    public record MemberPreview(
            String accountCode,
            String displayName,
            boolean isPoolPrimary,
            BigDecimal derivedBalance) {
    }

    /** A reconcilable account that closes on its own, through endpoint 4. */
    public record AccountPreview(
            String accountCode,
            String displayName,
            BigDecimal derivedBalance,
            LocalDateTime lastClosedAt,
            LocalDate lastReconDate) {
    }
}
