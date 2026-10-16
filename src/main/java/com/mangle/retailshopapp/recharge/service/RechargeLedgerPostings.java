package com.mangle.retailshopapp.recharge.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mangle.retailshopapp.accounting.LedgerPostingService;
import com.mangle.retailshopapp.accounting.PostedVoucher;
import com.mangle.retailshopapp.accounting.VoucherDraft;
import com.mangle.retailshopapp.accounting.VoucherLeg;
import com.mangle.retailshopapp.accounting.model.AccVoucher;
import com.mangle.retailshopapp.accounting.model.AccVoucherLine;
import com.mangle.retailshopapp.accounting.repo.AccAccountRepository;
import com.mangle.retailshopapp.accounting.repo.AccVoucherLineRepository;
import com.mangle.retailshopapp.accounting.repo.AccVoucherRepository;
import com.mangle.retailshopapp.recharge.domain.FloatConsumption;
import com.mangle.retailshopapp.recharge.domain.ResidualMode;
import com.mangle.retailshopapp.recharge.domain.SettlementMethod;
import com.mangle.retailshopapp.recharge.domain.SettlementPlan;
import com.mangle.retailshopapp.recharge.exception.FloatAccountNotDerivableException;
import com.mangle.retailshopapp.recharge.exception.NoCommissionRateException;
import com.mangle.retailshopapp.recharge.model.AccCommissionRate;
import com.mangle.retailshopapp.recharge.model.RcOperator;
import com.mangle.retailshopapp.recharge.model.RcRecharge;
import com.mangle.retailshopapp.recharge.model.RefRechargeRoute;
import com.mangle.retailshopapp.recharge.repo.AccCommissionRateRepository;

/**
 * The two-stage recharge posting (log §7.2, design-ledger.md §11.1).
 *
 * <p>⚠ TWO VOUCHERS PER SALE, AND THE SPLIT IS THE DECISION. §7.2 overturned
 * design-ledger.md:1215-1221, which posted the whole Sales voucher when the
 * payment settled:
 *
 * <table border="1">
 *   <caption>The life of one sale</caption>
 *   <tr><th>When</th><th>Voucher</th></tr>
 *   <tr><td>payment settled</td>
 *       <td>{@code Dr CASH_RECHARGE} (or {@code UPI_CLEARING}, or
 *           {@code AR_RECHARGE} on credit) / {@code Cr RCHG_PENDING}</td></tr>
 *   <tr><td>recharge completed</td>
 *       <td>{@code Dr RCHG_PENDING} / {@code Cr FLOAT_<wallet>} +
 *           {@code Cr INC_RCHG_COMM} (+ {@code Cr INC_RCHG_SURCHARGE})</td></tr>
 * </table>
 *
 * <p>Why not one voucher at either end. Posting everything at COMPLETED means
 * that between the operator taking cash and marking the recharge done,
 * physically-collected cash exists in the drawer and nowhere in the ledger —
 * across midnight that makes day 1's count over, and a recharge that fails and is
 * not refunded leaves cash in the drawer with no ledger entry at all. Posting
 * everything at SETTLED means every {@code FAILED} recharge needs a reversing
 * entry, which is the exact argument §2.7 used to reject recognising water
 * revenue at trip creation.
 *
 * <p>The two-stage form makes the FAILURE PATH FREE — "the single biggest
 * saving: the failure path is the common one". Nothing posts on failure; the
 * money simply stays in {@code RCHG_PENDING}, which is also what makes open
 * exposure a readable account balance rather than a query over status joins.
 *
 * <p><b>A {@code @Component}, not a ninth service.</b> §5.1 names eight services
 * and this is not one of them — it is the ledger adapter they share, holding the
 * leg construction, the rate lookup, the float derivation and the idempotency
 * keys in one place so that the settle and complete paths cannot drift on any of
 * them. Putting it in either service would duplicate it in the other.
 *
 * <p>Every method here is {@code propagation = MANDATORY}, matching
 * {@code LedgerPostingService}. §5.1 calls that "the single most valuable line in
 * this design": a future caller that forgets {@code @Transactional} throws on its
 * first call rather than silently committing the ledger write separately from the
 * operational one.
 */
@Component
public class RechargeLedgerPostings {

    static final String CASH_RECHARGE = "CASH_RECHARGE";
    static final String UPI_CLEARING = "UPI_CLEARING";
    static final String AR_RECHARGE = "AR_RECHARGE";
    static final String ADV_RECHARGE = "ADV_RECHARGE";
    static final String RCHG_PENDING = "RCHG_PENDING";
    static final String INC_RCHG_COMM = "INC_RCHG_COMM";
    static final String INC_RCHG_SURCHARGE = "INC_RCHG_SURCHARGE";

    private static final String SOURCE_RECHARGE_SALE = "RECHARGE_SALE";
    private static final String TYPE_SALES = "SALES";
    private static final String TYPE_JOURNAL = "JOURNAL";
    private static final String TYPE_PAYMENT = "PAYMENT";
    private static final String TYPE_RECEIPT = "RECEIPT";
    private static final String SOURCE_CORRECTION = "CORRECTION";
    private static final String SOURCE_REFUND = "REFUND";

    /**
     * {@code WA = 1.0} for every wallet the shop holds.
     *
     * <p>All four are FRD §7.2 pattern 2 — pay ₹X receive ₹X — so cost equals
     * face (log §2.4, §8.0). §3.5 says the weighted-average machinery is DORMANT,
     * NOT DEAD: it must not be deleted and must not be presented as day-one work.
     * Reading it from {@code acc_float_movement} would be the general form; with
     * every pattern at parity that query can only ever return 1.0, and a divide
     * over an empty movement table returns null, so the constant is both correct
     * and safer today. The hook for pattern 1 is {@link FloatConsumption#of}.
     */
    private static final BigDecimal WA_AT_PARITY = BigDecimal.ONE.setScale(8);

    private final LedgerPostingService ledger;
    private final AccCommissionRateRepository rates;
    private final AccAccountRepository accounts;
    private final AccVoucherRepository vouchers;
    private final AccVoucherLineRepository lines;

    public RechargeLedgerPostings(LedgerPostingService ledger,
                                  AccCommissionRateRepository rates,
                                  AccAccountRepository accounts,
                                  AccVoucherRepository vouchers,
                                  AccVoucherLineRepository lines) {
        this.ledger = ledger;
        this.rates = rates;
        this.accounts = accounts;
        this.vouchers = vouchers;
        this.lines = lines;
    }

    /**
     * Stage one: the cash is in, the recharge has not happened yet.
     *
     * <p>One debit leg per settlement line, because the three tenders land in
     * three different accounts and the day's reconciliation needs them apart:
     * cash in the drawer, UPI awaiting next-morning settlement, and a receivable
     * on a named customer. One credit leg for the whole basket, because
     * {@code RCHG_PENDING} is what the recharges will be drawn from.
     *
     * <p>The credit leg carries {@code partyCustId}: {@code AR_RECHARGE} has
     * {@code REQUIRES_PARTY = TRUE}, and {@code LedgerPostingService} refuses a
     * leg without it — "a receivable with no party cannot appear on anyone's
     * statement".
     *
     * @param paymentId the {@code rc_txn_header} id; the idempotency key's subject
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PostedVoucher postSettlement(int paymentId, SettlementPlan plan, Integer staffUserId,
                                        LocalDateTime when) {
        List<VoucherLeg> legs = new ArrayList<>();

        for (SettlementPlan.SettlementLine line : plan.settlementLines()) {
            // ⚠ lineType(), NOT name(). acc_voucher_line.PAYMENT_METHOD
            // foreign-keys ref_payment_line_type, whose three codes are CASH, UPI
            // and CRDT — the wire name is CREDIT and the column value is CRDT
            // (V9's seeds, and 2,844 historical rows). Using the enum's name here
            // fails fk_line_paymeth on every credit sale, which is a failure that
            // only appears when a basket actually carries credit.
            legs.add(switch (line.method()) {
                case CASH -> VoucherLeg.debit(CASH_RECHARGE, line.amount())
                        .withPaymentMethod(line.method().lineType());
                case UPI -> VoucherLeg.debit(UPI_CLEARING, line.amount())
                        .withPaymentMethod(line.method().lineType())
                        .withNarration(line.reference());
                case CREDIT -> VoucherLeg.debit(AR_RECHARGE, line.amount())
                        .withParty(line.customerId())
                        .withPaymentMethod(line.method().lineType())
                        .withNarration(line.note());
            });
        }

        legs.add(VoucherLeg.credit(RCHG_PENDING, plan.totalDue()));

        VoucherDraft draft = new VoucherDraft(
                TYPE_SALES,
                when.toLocalDate(),
                when,
                "Recharge sale settled, payment P-" + paymentId,
                SOURCE_RECHARGE_SALE,
                "rc_txn_header#" + paymentId,
                settlementKey(paymentId),
                null,
                staffUserId,
                legs);

        return ledger.post(draft);
    }

    /**
     * Stage two: the recharge happened, so the float is consumed and the income
     * is earned.
     *
     * <p>⚠ KEYED ON THE RECHARGE, NOT THE HEADER (log §7.2). That is what makes a
     * retry's completion a distinct post with no special-casing: the retry is a
     * new {@code rc_recharge} row under the SAME payment, so a header-keyed
     * idempotency key would collide with the original's completion.
     *
     * @throws NoCommissionRateException 422 — the income post is refused and the
     *         amount stays in {@code RCHG_PENDING}. Safe precisely because stage
     *         one already happened: the sale stands, the customer is not kept
     *         waiting, and a genuine miss means a config hole that should be loud
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PostedVoucher postCompletion(RcRecharge recharge, RcOperator operator,
                                        RefRechargeRoute route, Integer staffUserId,
                                        LocalDateTime when) {
        // ⚠ THE FLOAT ACCOUNT IS CHECKED BEFORE THE RATE, and the order is a
        // decision rather than an accident. Both can be wrong at once — BSNL on
        // DIRECT has neither a wallet nor a rate — and the rate message would win
        // if the lookup ran first, telling the user to add a commission rate for
        // a route the operator has no connection on. §2.4:86 gives BSNL no direct
        // connection at all, so no rate should ever be added there.
        //
        // The float derivation landing on nothing means THE ROUTE IS IMPOSSIBLE
        // for this operator, which is the more fundamental fault and the one
        // whose message can actually be acted on. Checking it first keeps §8.2's
        // rate refusal for what §8.2 is about: a config hole on a route that IS
        // reachable.
        String floatAccount = route.floatAccountFor(recharge.getOperatorCode());

        // Two distinct ways this fails, and both are now a 422 the counter can
        // act on rather than a 500 with the explanation buried in a log. A null
        // means the route has no derivation at all (SARAVATE); a missing account
        // means the derivation named a wallet nobody holds, which is what a
        // DIRECT pairing does for BSNL or any TV operator.
        if (floatAccount == null
                || accounts.findByAccountCodeIn(List.of(floatAccount)).isEmpty()) {
            throw new FloatAccountNotDerivableException(
                    recharge.getOperatorCode(), route.getCode(), floatAccount);
        }

        FloatConsumption consumption = consumptionFor(recharge, operator, route);

        List<VoucherLeg> legs = new ArrayList<>();

        // Dr the whole amount the customer paid for this line, surcharge
        // included: that is what stage one put into RCHG_PENDING for it.
        legs.add(VoucherLeg.debit(RCHG_PENDING, recharge.lineTotal()));

        // Cr the float by the FACE the portal will debit — A x (1 - r) — not by
        // the amount collected. This is the leg that reconciles against the
        // operator portal rupee for rupee.
        legs.add(VoucherLeg.credit(floatAccount, consumption.faceConsumed()));

        // Cr commission BY SUBTRACTION. Computing A x r separately is what puts a
        // plug figure in the voucher and stops it balancing to the paisa.
        //
        // ⚠ GUARDED, AND WITHOUT THE GUARD ONE LIVE OPERATOR COULD NOT BE SOLD AT
        // ALL. LedgerPostingService refuses a zero leg ("a leg cannot be zero"),
        // and a zero commission is not an edge case here: V25 seeds BIGTV at
        // RATE_PCT 0.000000 and V28 repoints its default route to A1TOPUP, which
        // is where that rate lives — so BIGTV is fully sellable, and every
        // completion threw LedgerPostingException, rolled the transaction back,
        // left the status at AWAITING_RECHARGE and returned a 500. The cash was
        // already collected and stuck in RCHG_PENDING with no exit.
        //
        // A zero rate MUST post rather than refuse. §8.4 is explicit: seed "at
        // zero rather than at a guess ... so the operator can trade from day
        // one", and "the tail can carry provisional zeros and be corrected by
        // journal". That is a different case from §8.2's MISSING rate, which does
        // refuse — a stated zero is a known placeholder, an absent row is a
        // config hole. At r = 0 the portal debits the full face value, so the
        // float leg carries the whole amount and no income is recognised, which
        // is exactly what "we do not know this rate yet" means.
        //
        // Found by code review, confirmed by execution. The arithmetic was
        // tested (FloatConsumptionTest.zeroRateAtParity asserts commission is
        // 0.00) and a voucher was never posted with it — the same gap between two
        // correct tests that hid the drawer identity.
        if (consumption.commission().signum() > 0) {
            legs.add(VoucherLeg.credit(INC_RCHG_COMM, consumption.commission()));
        }

        // Cr the shop's own charge as its OWN leg, computed directly rather than
        // by subtraction (log §15.11). Keeping it out of INC_RCHG_COMM is an
        // invariant rather than reporting taste: operator commission stays
        // exactly A - round(A x (1 - r), 2), so it remains checkable against the
        // rate card and the portal debit. Fold the surcharge in and no query can
        // say whether a discrepancy is a wrong rate or a surcharge.
        if (recharge.getSurcharge().signum() > 0) {
            legs.add(VoucherLeg.credit(INC_RCHG_SURCHARGE, recharge.getSurcharge()));
        }

        VoucherDraft draft = new VoucherDraft(
                TYPE_SALES,
                when.toLocalDate(),
                when,
                "Recharge completed, R-" + recharge.getRechargeId(),
                SOURCE_RECHARGE_SALE,
                "rc_recharge#" + recharge.getRechargeId(),
                completionKey(recharge.getRechargeId()),
                null,
                staffUserId,
                legs);

        return ledger.post(draft);
    }

    /**
     * Reverses a recharge's COMPLETION voucher, returning the money to
     * {@code RCHG_PENDING}.
     *
     * <p>⚠ WITHOUT THIS, TWO LEGAL TRANSITIONS LEFT THE LEDGER OVERSTATING
     * INCOME. {@code RechargeStatus} deliberately allows
     * {@code COMPLETED → FAILED} (the operator never delivered) and
     * {@code COMPLETED → VOIDED} (the sale should not exist), both added by log
     * §7.1. On either path stage two has already run, so {@code INC_RCHG_COMM},
     * {@code INC_RCHG_SURCHARGE} and the float consumption stayed recognised for
     * a recharge that did not happen — while the day summary dropped the sale
     * from takings and reported {@code heldPending} as zero, because
     * {@code RCHG_PENDING} had already been cleared. Cash in the drawer
     * reconciled to nothing.
     *
     * <p><b>A dated reversing JOURNAL, never an edit or a delete.</b> FRD §8.3
     * mandates exactly this form after an account's reconciliation point, and it
     * is correct before that point too — merely less tidy than the
     * delete-and-repost §8.3 permits there. Choosing between the two is what
     * {@code LedgerCorrectionRules} is for, and S2 shipped it with no caller; a
     * journal is the form that is right in BOTH cases, so this does not need that
     * decision in order to stop being wrong. The original voucher is left
     * untouched, which V23's trigger would enforce anyway.
     *
     * <p><b>⚠ THE CASH LEG IS NEVER TOUCHED.</b> design-api.md §3.2: "a void
     * never touches money the shop still holds." The customer genuinely paid and
     * the cash is genuinely in the drawer, so only the completion is reversed and
     * the amount returns to {@code RCHG_PENDING} — from where §7.4's residual
     * mechanism settles it, by handing it back or keeping it on a named
     * customer's account.
     *
     * <p>Idempotent on its own key, so reversing twice writes nothing. Returns
     * {@code null} when there is no completion voucher to reverse, which is the
     * ordinary {@code AWAITING_RECHARGE → FAILED} path — the common case, and the
     * one log §7.2 made free.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PostedVoucher reverseCompletion(RcRecharge recharge, Integer staffUserId,
                                           String reason, LocalDateTime when) {
        var original = vouchers.findByIdempotencyKey(completionKey(recharge.getRechargeId()));
        if (original.isEmpty()) {
            // Never completed, so there is nothing recognised to take back.
            return null;
        }
        AccVoucher completion = original.get();

        List<VoucherLeg> mirrored = new ArrayList<>();
        for (AccVoucherLine line : lines.findByVoucherIdOrderByLineNo(completion.getVoucherId())) {
            // Each leg flipped: what was debited is credited back, and the other
            // way round. Mirroring rather than recomputing matters — recomputing
            // would use today's rate and today's surcharge, and so would reverse
            // a different amount from the one that was posted.
            VoucherLeg leg = line.getDrAmount().signum() > 0
                    ? VoucherLeg.credit(line.getAccountCode(), line.getDrAmount())
                    : VoucherLeg.debit(line.getAccountCode(), line.getCrAmount());
            if (line.getPartyCustId() != null) {
                leg = leg.withParty(line.getPartyCustId());
            }
            if (line.getPaymentMethod() != null) {
                leg = leg.withPaymentMethod(line.getPaymentMethod());
            }
            mirrored.add(leg.withNarration("reversal of voucher " + completion.getVoucherId()));
        }

        VoucherDraft draft = new VoucherDraft(
                TYPE_JOURNAL,
                // Dated TODAY, not the day the completion was posted. FRD §8.3:
                // "a JOURNAL dated the day the mistake was found". Back-dating it
                // would silently restate a day that may already be reconciled.
                when.toLocalDate(),
                when,
                "Reversal of R-%d completion: %s".formatted(
                        recharge.getRechargeId(),
                        reason == null || reason.isBlank() ? "no reason given" : reason),
                SOURCE_CORRECTION,
                "rc_recharge#" + recharge.getRechargeId(),
                reversalKey(recharge.getRechargeId()),
                completion.getVoucherId(),
                staffUserId,
                mirrored);

        return ledger.post(draft);
    }

    /**
     * Money stops being held: the residual of a lower retry, or a refund
     * (log §7.4, §7.3).
     *
     * <p><b>⚠ NOTHING IS REVERSED HERE, and working out why is the whole
     * saving.</b> Under §7.2 a failed recharge never consumed float and never
     * recognised income — "FAILED needs no reversing entry at all. The money
     * simply stays in {@code RCHG_PENDING}." So this is not a correction of an
     * earlier posting; it is the second half of a sale that was always going to
     * have one. §7.3 states the refund voucher in exactly this form and calls it
     * "now trivial".
     *
     * <p>The four paths that reach this method, and what each has already done:
     *
     * <ol>
     *   <li><b>retry at a lower amount</b> — nothing posted at retry time; this
     *       settles what the retry will not consume;
     *   <li><b>refund after {@code AWAITING_RECHARGE → FAILED}</b> — nothing was
     *       ever posted beyond stage one;
     *   <li><b>refund after {@code COMPLETED → FAILED}</b> — the completion was
     *       already reversed by {@link #reverseCompletion} when the status moved,
     *       so the money is back in {@code RCHG_PENDING} and this is again the
     *       only posting left;
     *   <li><b>a {@code VOIDED} sale's residual</b> — same as (3). §7.4:
     *       "Decisions 1 and 4 converge on one screen and one posting rule."
     * </ol>
     *
     * <p>None of the four needs a reversal written here, and a second one in
     * case (3) would take the income back twice.
     *
     * <p>The credit side is fixed by the mode and never by the caller's opinion
     * (§7.4's table). A {@code PAYMENT} voucher rather than a {@code JOURNAL}:
     * money genuinely leaves, which is what {@code ref_voucher_type}'s "Money
     * paid out" is, and a journal would file an ordinary refund as a correction.
     *
     * @param customerId required by both non-cash modes, whose accounts carry
     *                   {@code REQUIRES_PARTY = TRUE}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PostedVoucher postResidual(long failedRechargeId, BigDecimal amount, ResidualMode mode,
                                      Integer customerId, Integer staffUserId, String narration,
                                      LocalDateTime when) {
        VoucherLeg credit = switch (mode) {
            case CASH -> VoucherLeg.credit(CASH_RECHARGE, amount)
                    .withPaymentMethod(SettlementMethod.CASH.lineType());
            case KEEP_AS_ADVANCE -> VoucherLeg.credit(ADV_RECHARGE, amount).withParty(customerId);
            case CREDIT_REDUCTION -> VoucherLeg.credit(AR_RECHARGE, amount).withParty(customerId);
        };

        VoucherDraft draft = new VoucherDraft(
                TYPE_PAYMENT,
                when.toLocalDate(),
                when,
                narration,
                SOURCE_REFUND,
                "rc_recharge#" + failedRechargeId,
                residualKey(failedRechargeId),
                null,
                staffUserId,
                List.of(VoucherLeg.debit(RCHG_PENDING, amount), credit));

        return ledger.post(draft);
    }

    /**
     * More money comes in, because the retry costs more than was collected
     * (log §7.4's "opposite direction").
     *
     * <p>§7.4 describes this as the retry's completion debiting
     * {@code RCHG_PENDING 600} "plus the extra ₹50 from {@code CASH_RECHARGE}
     * (collected now) or {@code AR_RECHARGE} (owed)" — one voucher drawing on two
     * sources. <b>It is posted here as the mirror of the residual instead</b>:
     * the extra is taken into {@code RCHG_PENDING} now, and the retry's
     * completion then draws its whole line total from that one account like any
     * other recharge. The two forms put identical net amounts in identical
     * accounts; this one leaves {@link #postCompletion} untouched, which is what
     * §7.2 means by "keying the second on the recharge row … makes a retry's
     * completion a distinct post with no special-casing".
     *
     * <p>A {@code RECEIPT} voucher, and the method is one of the three the settle
     * path already supports rather than the two §7.4 names — the tender line and
     * the debit leg are the same machinery as {@link #postSettlement}, so a
     * customer topping up by UPI costs nothing extra to accept.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PostedVoucher postTopUp(long failedRechargeId, BigDecimal amount,
                                   SettlementMethod method, Integer customerId,
                                   Integer staffUserId, String narration, LocalDateTime when) {
        VoucherLeg debit = switch (method) {
            case CASH -> VoucherLeg.debit(CASH_RECHARGE, amount)
                    .withPaymentMethod(method.lineType());
            case UPI -> VoucherLeg.debit(UPI_CLEARING, amount)
                    .withPaymentMethod(method.lineType());
            case CREDIT -> VoucherLeg.debit(AR_RECHARGE, amount)
                    .withParty(customerId)
                    .withPaymentMethod(method.lineType());
        };

        VoucherDraft draft = new VoucherDraft(
                TYPE_RECEIPT,
                when.toLocalDate(),
                when,
                narration,
                SOURCE_RECHARGE_SALE,
                "rc_recharge#" + failedRechargeId,
                topUpKey(failedRechargeId),
                null,
                staffUserId,
                List.of(debit, VoucherLeg.credit(RCHG_PENDING, amount)));

        return ledger.post(draft);
    }

    /**
     * {@code REFUND:rc_recharge#<id>:RESIDUAL} — keyed on the FAILED recharge.
     *
     * <p>Keyed on the failure rather than on the retry that may follow it,
     * because all four paths into {@link #postResidual} have a failed recharge
     * and only one of them has a retry. V29's unique key makes one failure
     * resolvable once, so this key is unique by the same guarantee.
     */
    static String residualKey(long failedRechargeId) {
        return SOURCE_REFUND + ":rc_recharge#" + failedRechargeId + ":RESIDUAL";
    }

    /** {@code RECHARGE_SALE:rc_recharge#<id>:TOPUP}, keyed on the failed recharge. */
    static String topUpKey(long failedRechargeId) {
        return SOURCE_RECHARGE_SALE + ":rc_recharge#" + failedRechargeId + ":TOPUP";
    }

    /**
     * {@code CORRECTION:rc_recharge#<id>:COMPLETION_REVERSED}.
     *
     * <p>A different source and a different stage from the completion it reverses,
     * which is what §5.4 says the {@code :STAGE} suffix is for: "delete-and-repost
     * reuses the same key; a correcting journal gets a different one, so both can
     * coexist."
     */
    static String reversalKey(long rechargeId) {
        return SOURCE_CORRECTION + ":rc_recharge#" + rechargeId + ":COMPLETION_REVERSED";
    }

    /**
     * The rate lookup and the arithmetic, exposed so the counter can show what a
     * completion WILL post before it posts it.
     *
     * @throws NoCommissionRateException 422 with the operator's display name, so
     *         the counter can show "Airtel has no commission rate on file — add
     *         one to complete"
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public FloatConsumption consumptionFor(RcRecharge recharge, RcOperator operator,
                                           RefRechargeRoute route) {
        // :rechargeDate, not today. §4.2: a recharge entered a day late must use
        // the rate that applied on the day it HAPPENED.
        LocalDate asOf = recharge.getRechargeDttm().toLocalDate();

        List<AccCommissionRate> found = rates.lookup(
                recharge.getOperatorCode(), route.getCode(), "*",
                recharge.getAmount(), asOf, PageRequest.of(0, 1));

        if (found.isEmpty()) {
            throw new NoCommissionRateException(operator.getDisplayName(),
                    recharge.getOperatorCode(), route.getCode());
        }

        // The rate applies to the RECHARGE amount, never to the surcharge: the
        // operator pays commission on what it delivered, and the surcharge is the
        // shop's own charge that the operator knows nothing about.
        return FloatConsumption.of(recharge.getAmount(),
                found.get(0).rateAsFraction(), WA_AT_PARITY);
    }

    /**
     * {@code <SOURCE_TYPE>:<table>#<id>:<STAGE>} (design-ledger.md §5.4).
     *
     * <p>§5.4's format is authoritative over log §7.2's shorthand
     * ({@code rc_txn_header#<id>:SETTLE}), which omits the source prefix. The
     * {@code :STAGE} suffix is what lets a delete-and-repost reuse the same key
     * while a correcting journal gets a different one.
     */
    static String settlementKey(int paymentId) {
        return SOURCE_RECHARGE_SALE + ":rc_txn_header#" + paymentId + ":SETTLED";
    }

    static String completionKey(long rechargeId) {
        return SOURCE_RECHARGE_SALE + ":rc_recharge#" + rechargeId + ":COMPLETED";
    }
}
