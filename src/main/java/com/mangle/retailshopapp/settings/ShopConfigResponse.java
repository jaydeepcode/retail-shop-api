package com.mangle.retailshopapp.settings;

import java.math.BigDecimal;

/**
 * The shop-wide config every screen needs, whatever section it is in
 * (next-session-s5.md item 3).
 *
 * <p>⚠ <b>WHY THIS EXISTS AT ALL: ₹5 WAS WRITTEN FOUR TIMES.</b>
 * {@code change.display.threshold} is seeded at {@code V9:229} and the recharge
 * counter reads it through {@code /recharge/config} — but the ticket screen had
 * no config endpoint, so the figure sat as a literal in
 * {@code TicketResponseMapper} and again in {@code booking.component.ts}, both
 * carrying a comment saying so. The close screen needs it too, which artboard 9
 * confirms: its <i>Small change rounding</i> button is captioned "likely — the
 * difference is under ₹5". A third screen reading a fourth literal is what this
 * endpoint prevents.
 *
 * <p>⚠ <b>{@code bank.default.account} IS DELIBERATELY NOT HERE</b> (log §9.3,
 * item 3). §1.2 resolves the bank SERVER-SIDE when a UPI settlement posts, and
 * shipping the account code to a client invites a client to send it back — at
 * which point which bank the shop's money moves through is decided by whatever
 * the browser last cached. The field is not missing; it is withheld.
 *
 * <p>Not endpoint 12, which returns {@code List<AccountResponse>}: settings would
 * force that into a wrapper and make the account picker carry things no picker
 * needs. And not an addition to {@code /recharge/config}, which keeps operators,
 * routes and presets — the counter-specific half — and reads the shared service
 * for the rest.
 *
 * @param changeDisplayThreshold change below this is not shown (log §14.5). The
 *                               rule stays server-side in {@code TenderPlan};
 *                               this is the figure a screen captions a button
 *                               with, never a second implementation of the rule
 * @param floatImpsFee           the flat IMPS charge on a float top-up, which is
 *                               endpoint 19's {@code transferFee} default
 *                               (log §2.5). A client shows it and may override it
 *                               — E7's cash top-up sends {@code 0}, because no
 *                               IMPS fee arises on cash
 * @param fyStartMonth           4 for the Indian financial year (design-domain.md
 *                               §9.1), read from {@code rc_setting} rather than
 *                               compiled in
 * @param currentFinancialYear   the label of the year containing today,
 *                               {@code "2026-27"}. ⚠ <b>Derived, never a stored
 *                               active year</b> — §9.3 rejects a stored global
 *                               one outright, because the first time it reaches
 *                               the credit-balance query 33 of 34 open balances
 *                               vanish from the screen and staff stop chasing
 *                               debt
 */
public record ShopConfigResponse(
        BigDecimal changeDisplayThreshold,
        BigDecimal floatImpsFee,
        int fyStartMonth,
        String currentFinancialYear) {
}
