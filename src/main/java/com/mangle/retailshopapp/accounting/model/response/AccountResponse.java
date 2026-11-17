package com.mangle.retailshopapp.accounting.model.response;

import com.mangle.retailshopapp.accounting.model.AccAccount;

/**
 * One account on the picker list (endpoint 12).
 *
 * <p>§1.3: "active accounts with type, business, pool and the {@code ALLOW_MANUAL}
 * flag". Deliberately not a wrapper record with settings on it — that was
 * considered and declined in next-session-s5.md item 3, because it would make
 * the account picker carry things no picker needs. {@code GET /config} is where
 * the settings went.
 *
 * <p>⚠ <b>{@code allowManual} IS SHIPPED RATHER THAN APPLIED, AND THAT IS THE
 * POINT OF RETURNING IT.</b> A7 is a rule about the generic voucher endpoints,
 * not about the ledger: {@code UPI_CLEARING}, {@code RCHG_PENDING} and the four
 * floats are machine-owned and are moved only by the endpoints that own them —
 * #6 for a settlement, #19 for a top-up, #4 for a float correction. A client that
 * can see the flag can grey the account out and say why, instead of offering it
 * and collecting a 422. The server refuses it regardless; the flag is so the
 * screen does not have to guess which accounts it may offer.
 *
 * <p>{@code floatPattern} comes back because non-null is what MAKES an account a
 * float (V21's header) — there is no {@code IS_FLOAT} column, so a client that
 * needs to know reads this rather than pattern-matching the code prefix.
 *
 * @param isPoolPrimary the member a pool's whole variance lands on (A2, E17d).
 *                      Null-free: false on everything that is not in a pool
 * @param reconcilable  the account has an independent source to count against —
 *                      a drawer, a bank statement, an operator portal
 */
public record AccountResponse(
        String accountCode,
        String displayName,
        String accountType,
        String businessCode,
        String reportGroup,
        String cashPool,
        boolean isPoolPrimary,
        boolean allowManual,
        boolean requiresParty,
        boolean reconcilable,
        String floatPattern,
        int sortOrder) {

    public static AccountResponse from(AccAccount a) {
        return new AccountResponse(
                a.getAccountCode(),
                a.getDisplayName(),
                a.getAccountType(),
                a.getBusinessCode(),
                a.getReportGroup(),
                a.getCashPool(),
                a.isPoolPrimary(),
                a.isAllowManual(),
                a.isRequiresParty(),
                a.isReconcilable(),
                a.getFloatPattern(),
                a.getSortOrder());
    }
}
