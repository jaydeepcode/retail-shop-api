package com.mangle.retailshopapp.recharge.domain;

/**
 * Where money the shop is holding goes when it stops being held
 * (log §7.4's table).
 *
 * <p>⚠ ONE ENUM FOR FOUR SITUATIONS, which is log §7.4's whole point: "Decisions
 * 1 and 4 converge on one screen and one posting rule." The residual left by a
 * retry at a lower amount, a refund of a failed recharge, and the amount a
 * {@code VOIDED} completion returns to {@code RCHG_PENDING} are the same money in
 * the same account, and they leave it the same three ways. Modelling them apart
 * would have produced three nearly-identical posting paths that could drift.
 *
 * <p>The credit side of the voucher is fixed by the mode, and the debit is always
 * {@code RCHG_PENDING}:
 *
 * <table border="1">
 *   <caption>log §7.4</caption>
 *   <tr><th>mode</th><th>credits</th><th>customer</th></tr>
 *   <tr><td>{@code CASH}</td><td>{@code CASH_RECHARGE}</td><td>no — stays anonymous</td></tr>
 *   <tr><td>{@code KEEP_AS_ADVANCE}</td><td>{@code ADV_RECHARGE}</td><td>yes, must be named</td></tr>
 *   <tr><td>{@code CREDIT_REDUCTION}</td><td>{@code AR_RECHARGE}</td><td>already named</td></tr>
 * </table>
 *
 * <p><b>{@code CASH} is the default and the reason is not arbitrary</b> (§7.4):
 * handing the cash back needs no customer lookup, which keeps the walk-in
 * anonymous as FRD §8.7:316 requires. {@code KEEP_AS_ADVANCE} posts to the
 * account §2.8 established, where two customers already carry +₹37 and +₹15 from
 * exactly this kind of overpayment — the case is evidenced in live data, not
 * hypothetical.
 */
public enum ResidualMode {

    /** Hand it back over the counter. No customer, no lookup. */
    CASH(false),

    /** Keep it on the customer's account against a future visit (§2.8). */
    KEEP_AS_ADVANCE(true),

    /**
     * Reduce what the customer owes, because the sale went out on credit and no
     * cash was ever collected to hand back (§7.4).
     */
    CREDIT_REDUCTION(true);

    private final boolean needsCustomer;

    ResidualMode(boolean needsCustomer) {
        this.needsCustomer = needsCustomer;
    }

    /**
     * Whether this mode can only be used against a named customer.
     *
     * <p>Both of the non-cash modes credit an account with
     * {@code REQUIRES_PARTY = TRUE}, and {@code LedgerPostingService} refuses a
     * leg without a party — "a receivable with no party cannot appear on anyone's
     * statement". Asking here first turns that into a 422 naming the rule rather
     * than a posting failure.
     */
    public boolean needsCustomer() {
        return needsCustomer;
    }
}
