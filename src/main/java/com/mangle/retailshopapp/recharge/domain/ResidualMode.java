package com.mangle.retailshopapp.recharge.domain;

/**
 * Where money the shop is holding goes when it stops being held
 * (log §7.4's table, as amended 2026-10-21).
 *
 * <p>⚠ ONE ENUM FOR EVERY SUCH SITUATION, which is log §7.4's whole point:
 * "Decisions 1 and 4 converge on one screen and one posting rule." The residual
 * left by a retry at a lower amount and a refund of a failed recharge are the
 * same money in the same account, and they leave it the same ways. Modelling them
 * apart would have produced nearly-identical posting paths that could drift.
 *
 * <p>The credit side of the voucher is fixed by the mode, and the debit is always
 * {@code RCHG_PENDING}:
 *
 * <table border="1">
 *   <caption>log §7.4, amended</caption>
 *   <tr><th>mode</th><th>credits</th><th>customer</th><th>note</th></tr>
 *   <tr><td>{@code CASH}</td><td>{@code CASH_RECHARGE}</td><td>no — stays anonymous</td><td>optional</td></tr>
 *   <tr><td>{@code UPI}</td><td>{@code UPI_CLEARING}</td><td>no — stays anonymous</td><td><b>required</b></td></tr>
 *   <tr><td>{@code CREDIT_REDUCTION}</td><td>{@code AR_RECHARGE}</td><td>already named</td><td>optional</td></tr>
 * </table>
 *
 * <p><b>{@code CASH} is the default and the reason is not arbitrary</b> (§7.4):
 * handing the cash back needs no customer lookup, which keeps the walk-in
 * anonymous as FRD §8.7:316 requires. {@code UPI} keeps that property for a sale
 * that arrived by UPI, because {@code UPI_CLEARING} is {@code REQUIRES_PARTY =
 * FALSE} (V21:291).
 *
 * <p>⚠ {@code KEEP_AS_ADVANCE} WAS WITHDRAWN FROM THIS ENUM on 2026-10-21, and
 * {@code UPI} is what made that safe. §7.4's table had no UPI row — it was
 * written assuming the original was cash or credit — so a UPI-only sale could
 * reach only {@code KEEP_AS_ADVANCE}, and that mode is a one-way door: it credits
 * {@code ADV_RECHARGE}, which nothing debits, {@code SettlementMethod} has no
 * {@code ADVANCE} member to draw it down, and {@code CreditLedgerService
 * .positionOf} reads the {@code rc_credit_req} tally and so cannot see it. The
 * liability changed shape instead of leaving. Each original now has a route home
 * by the rail it arrived on: cash hands cash back, credit reduces the tab, UPI
 * refunds by UPI.
 *
 * <p>The {@code ADV_RECHARGE} account stays seeded in V21 — unseeding costs a
 * migration for no gain, and S7 may want it with a drawdown path attached. ⚠ This
 * withdrawal is scoped to the RECHARGE residual path. {@code ADV_WATER} and the
 * water side are untouched: §2.8's worked example is a water trip, and prepaid
 * trips are an ordinary thing there.
 */
public enum ResidualMode {

    /** Hand it back over the counter. No customer, no lookup. */
    CASH(false, false),

    /**
     * Send it back down the rail it came in on, for a sale settled by UPI.
     *
     * <p>The exact mirror of {@code postSettlement}'s UPI tender leg: the sale
     * debited {@code UPI_CLEARING} and this credits it, so both legs return to
     * zero and nothing is left owing anywhere.
     *
     * <p>⚠ {@code UPI_CLEARING} is {@code IS_RECONCILABLE = TRUE} (V21:291), which
     * is where the accountability for this mode comes from: the outgoing amount
     * surfaces when UPI is reconciled against the bank. That is strictly stronger
     * than a note in a field, because reconciliation notices an ABSENCE and a
     * note cannot.
     */
    UPI(false, true),

    /**
     * Reduce what the customer owes, because the sale went out on credit and no
     * cash was ever collected to hand back (§7.4).
     */
    CREDIT_REDUCTION(true, false);

    private final boolean needsCustomer;
    private final boolean needsReference;

    ResidualMode(boolean needsCustomer, boolean needsReference) {
        this.needsCustomer = needsCustomer;
        this.needsReference = needsReference;
    }

    /**
     * Whether this mode can only be used against a named customer.
     *
     * <p>{@code CREDIT_REDUCTION} credits an account with
     * {@code REQUIRES_PARTY = TRUE}, and {@code LedgerPostingService} refuses a
     * leg without a party — "a receivable with no party cannot appear on anyone's
     * statement". Asking here first turns that into a 422 naming the rule rather
     * than a posting failure.
     *
     * <p>Both of the other modes are false, and for the same reason in both
     * cases: {@code CASH_RECHARGE} and {@code UPI_CLEARING} are
     * {@code REQUIRES_PARTY = FALSE}, so the walk-in stays anonymous
     * (FRD §8.7:316).
     */
    public boolean needsCustomer() {
        return needsCustomer;
    }

    /**
     * Whether this mode can only be used with a reference in the note.
     *
     * <p>{@code UPI} alone, and it is the point of the mode rather than a
     * formality: the note is where the UPI transaction reference goes, and that
     * reference is the only trace from this voucher back to the actual transfer.
     * A blank one is discovered at reconciliation time, when the transfer can no
     * longer be reconstructed.
     *
     * <p>⚠ Stated here rather than in the service, because an earlier version of
     * exactly this shape went wrong the other way round: review finding 11 found
     * {@code voidRecharge} hardcoding a rule {@code RechargeStatus} claimed to
     * own, so the enum's answer and the live behaviour could differ. The service
     * asks; it does not decide.
     */
    public boolean needsReference() {
        return needsReference;
    }
}
