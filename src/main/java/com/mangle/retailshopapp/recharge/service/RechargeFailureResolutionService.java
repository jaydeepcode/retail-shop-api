package com.mangle.retailshopapp.recharge.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mangle.retailshopapp.credit.model.RcCreditReq;
import com.mangle.retailshopapp.credit.model.RcTxnHeader;
import com.mangle.retailshopapp.credit.repo.RcCreditReqRepository;
import com.mangle.retailshopapp.credit.repo.RcTxnHeaderRepository;
import com.mangle.retailshopapp.recharge.domain.FailureResolution;
import com.mangle.retailshopapp.recharge.domain.Outstanding;
import com.mangle.retailshopapp.recharge.domain.RechargeStatus;
import com.mangle.retailshopapp.recharge.domain.ResidualMode;
import com.mangle.retailshopapp.recharge.domain.RetryPlan;
import com.mangle.retailshopapp.recharge.exception.CreditRequiresCustomerException;
import com.mangle.retailshopapp.recharge.exception.FailureAlreadyResolvedException;
import com.mangle.retailshopapp.recharge.exception.IllegalStatusTransitionException;
import com.mangle.retailshopapp.recharge.exception.PaymentNotFound;
import com.mangle.retailshopapp.recharge.exception.RechargeNotFound;
import com.mangle.retailshopapp.recharge.exception.RefundModeNotAvailableException;
import com.mangle.retailshopapp.recharge.exception.UnsettledDifferenceException;
import com.mangle.retailshopapp.recharge.exception.UpiRequiresReferenceException;
import com.mangle.retailshopapp.recharge.model.RcRecharge;
import com.mangle.retailshopapp.recharge.model.RcTxnPayment;
import com.mangle.retailshopapp.recharge.model.RcTxnPaymentId;
import com.mangle.retailshopapp.recharge.repo.RcRechargeRepository;
import com.mangle.retailshopapp.recharge.repo.RcTxnPaymentRepository;
import com.mangle.retailshopapp.settlement.SettlementMethod;

/**
 * Resolving a failure: the retry and the refund (design-api.md §3.2 #12 and
 * #13, §5.1; log §7.4, §7.3).
 *
 * <p><b>⚠ WITHOUT THIS CLASS REQUIREMENT 5 IS UNIMPLEMENTABLE.</b> Before it, a
 * failed recharge could be recorded and then nothing could be done about it:
 * {@code /fail} worked and the two endpoints that resolve a failure did not
 * exist. On a counter that is the common path, not an edge — log §7.2 calls the
 * failure path "the common one".
 *
 * <p><b>The invariant, and it is enforced here rather than in the
 * controller.</b> §3.3: the retry "carries no new payment: {@code paymentId} is
 * the original's. That single fact is the invariant requirement 5 is really
 * asking for." So a retry is a new {@code rc_recharge} row on the SAME
 * {@code (TXN_ID, SEQ_NO)} as the attempt it replaces — no second payment, no
 * second item line — linked by {@code FAILED_RECHARGE_ID}.
 *
 * <p><b>⚠ WHICH OF THE PATHS POST, worked out before any were written.</b> Under
 * log §7.2 a failed recharge never consumed float and never recognised income,
 * so there is nothing to reverse on any path here:
 *
 * <table border="1">
 *   <caption>what each resolution posts</caption>
 *   <tr><th>path</th><th>posts</th></tr>
 *   <tr><td>retry at the same amount</td><td><b>nothing</b> — the new row posts at its own completion</td></tr>
 *   <tr><td>retry at a lower amount</td><td>the residual leaves {@code RCHG_PENDING} (§7.4)</td></tr>
 *   <tr><td>retry at a higher amount</td><td>the top-up enters {@code RCHG_PENDING} (§7.4)</td></tr>
 *   <tr><td>refund</td><td>the whole line total leaves {@code RCHG_PENDING} (§7.3)</td></tr>
 * </table>
 *
 * <p>A recharge that reached {@code FAILED} from {@code COMPLETED} had its
 * completion reversed at that moment by {@code markFailed}, so by the time it
 * arrives here it is indistinguishable from one that never completed. Writing a
 * reversal here as well would take the income back twice.
 *
 * <p><b>One transaction, always.</b> §5.1's table says of #12: "All or nothing —
 * a retry must never exist without its parent being marked resolved, and the
 * difference owed must never be recorded without the retry." That is why the
 * difference is settled in this call rather than in a second request (§3.3:
 * "which is what keeps it in one transaction").
 */
@Service
public class RechargeFailureResolutionService {

    private final RcRechargeRepository recharges;
    private final RcTxnPaymentRepository payments;
    private final RcTxnHeaderRepository headers;
    private final RcCreditReqRepository creditRequests;
    private final RechargeFulfilmentService fulfilment;
    private final CreditLedgerService credit;
    private final CustomerDirectoryService customers;
    private final RechargeLedgerPostings ledger;
    private final Clock clock;

    public RechargeFailureResolutionService(RcRechargeRepository recharges,
                                            RcTxnPaymentRepository payments,
                                            RcTxnHeaderRepository headers,
                                            RcCreditReqRepository creditRequests,
                                            RechargeFulfilmentService fulfilment,
                                            CreditLedgerService credit,
                                            CustomerDirectoryService customers,
                                            RechargeLedgerPostings ledger,
                                            Clock clock) {
        this.recharges = recharges;
        this.payments = payments;
        this.headers = headers;
        this.creditRequests = creditRequests;
        this.fulfilment = fulfilment;
        this.credit = credit;
        this.customers = customers;
        this.ledger = ledger;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // #12 — retry
    // ------------------------------------------------------------------

    /**
     * Retries a failed recharge under the original payment (§3.2 #12).
     *
     * <p>Ordered so the cheapest rejections happen before any write, the same
     * shape §4.3 gives the settle path: the plan is pure arithmetic, the modes
     * are checked against the original payment, and only then does anything
     * change.
     *
     * @param amount the new FACE VALUE; the surcharge carries over unchanged
     * @param difference how a lower retry hands the remainder back — required
     *                   when, and only when, there is a remainder
     * @param topUp how a higher retry takes the extra — required when, and only
     *              when, there is one
     */
    @Transactional
    public Retried retry(int failedRechargeId, BigDecimal amount, Settlement difference,
                         Settlement topUp, Integer staffUserId) {
        LocalDateTime now = LocalDateTime.now(clock);

        RcRecharge failed = requireFailed(failedRechargeId);
        RetryPlan plan = RetryPlan.of(failed.getAmount(), failed.getSurcharge(), amount);

        // --- Validate the settlement before anything is written. ---------
        requireSettlementMatches(plan, difference, topUp);

        Tender residualTender = null;
        Tender topUpTender = null;
        if (plan.differenceOwed().signum() > 0) {
            residualTender = resolveResidual(failed, difference);
        } else if (plan.topUpDue().signum() > 0) {
            topUpTender = resolveTopUp(topUp);
        }

        // --- The parent closes first. ------------------------------------
        // RETRIED is terminal, so the §2.2(3) "at most one open attempt per item
        // line" check the retry insert will face can only pass once this has
        // happened. Inserting the retry first would make the line momentarily
        // carry two open attempts and trip an invariant that is correct.
        fulfilment.resolveFailure(failedRechargeId, RechargeStatus.RETRIED, staffUserId,
                "retried at " + plan.retryAmount().toPlainString());

        // --- The retry itself. -------------------------------------------
        RcRecharge retry = new RcRecharge();
        retry.setRechargeKind(failed.getRechargeKind());
        retry.setOperatorCode(failed.getOperatorCode());
        // The ORIGINAL's route, not today's default for the operator. The route
        // is a completion-time decision (log §14.6) and is overridable there, so
        // carrying the original's asserts nothing new; re-defaulting it would
        // silently change a choice if the operator's default has moved since.
        retry.setRouteCode(failed.getRouteCode());
        retry.setTargetNumber(failed.getTargetNumber());
        retry.setDishtvNo(failed.getDishtvNo());
        retry.setAmount(plan.retryAmount());
        retry.setSurcharge(plan.surcharge());
        retry.setRechargeDttm(now);
        retry.setStatusCode(RechargeStatus.AWAITING_RECHARGE);
        // ⚠ THE SAME PAYMENT AND THE SAME ITEM LINE. §3.3's invariant.
        retry.setTxnId(failed.getTxnId());
        retry.setSeqNo(failed.getSeqNo());
        retry.setFailedRechargeId(failedRechargeId);
        retry.setCreBy(staffUserId);
        retry.setCreDttm(now);
        // saveAndFlush so V29's uq_rech_retry fires HERE, inside this
        // transaction, rather than at commit where the handler cannot tell which
        // constraint refused.
        recharges.saveAndFlush(retry);

        // --- The difference, in the same transaction. ---------------------
        Outstanding position = null;
        if (residualTender != null) {
            position = settleResidual(failed, residualTender, plan.differenceOwed(),
                    "Retry of R-%d at %s: %s returned".formatted(
                            failedRechargeId, plan.retryAmount().toPlainString(),
                            plan.differenceOwed().toPlainString()),
                    staffUserId, now);
        } else if (topUpTender != null) {
            position = collectTopUp(failed, topUpTender, plan.topUpDue(),
                    "Retry of R-%d at %s: %s collected".formatted(
                            failedRechargeId, plan.retryAmount().toPlainString(),
                            plan.topUpDue().toPlainString()),
                    staffUserId, now);
        }

        return new Retried(failed, retry, plan,
                residualTender != null ? residualTender.mode() : null,
                topUpTender != null ? topUpTender.method() : null,
                position, now);
    }

    // ------------------------------------------------------------------
    // #13 — refund
    // ------------------------------------------------------------------

    /**
     * Refunds a failed recharge (§3.2 #13).
     *
     * <p>⚠ THE WHOLE LINE TOTAL, INCLUDING THE SURCHARGE. The shop's own fee was
     * charged for delivering a recharge that did not happen, and the amount
     * sitting in {@code RCHG_PENDING} for this line is {@code amount +
     * surcharge} — stage one credited the line total there. Refunding only the
     * face value would leave the fee stranded in a machine-owned account with no
     * exit, which is the shape of bug §7.1(c) warns the open-exposure figure
     * exists to make visible.
     *
     * <p>⚠ NO REVERSAL, AND THAT IS §7.3's POINT: "{@code FAILED → REFUNDED}
     * involves no float and no commission, because under §7.2 a failed recharge
     * never consumed any." The refund is one two-leg voucher.
     *
     * @param amount what the client believes it is refunding. Checked against
     *               the line total rather than trusted — a client that disagrees
     *               about the figure is refused, not quietly overruled
     * @param note free text, and the UPI transaction reference when
     *             {@code mode = UPI}, where {@link ResidualMode#needsReference()}
     *             makes it mandatory. ⚠ It was accepted by
     *             {@code RefundRechargeRequest} and DROPPED here until
     *             2026-10-21 — {@code new Settlement(mode, customerId, null)} —
     *             which went unnoticed because no mode did anything with it
     */
    @Transactional
    public Refunded refund(int failedRechargeId, BigDecimal amount, ResidualMode mode,
                           Integer customerId, String note, Integer staffUserId) {
        LocalDateTime now = LocalDateTime.now(clock);

        RcRecharge failed = requireFailed(failedRechargeId);
        BigDecimal lineTotal = failed.getAmount().add(failed.getSurcharge());

        if (amount != null && amount.compareTo(lineTotal) != 0) {
            throw UnsettledDifferenceException.refundMustBeWhole(lineTotal, amount);
        }

        Tender tender = resolveResidual(failed, new Settlement(mode, customerId, note));

        fulfilment.resolveFailure(failedRechargeId, RechargeStatus.REFUNDED, staffUserId,
                "refunded " + lineTotal.toPlainString() + " as " + mode.name());

        Outstanding position = settleResidual(failed, tender, lineTotal,
                "Refund of failed R-%d: %s".formatted(failedRechargeId, lineTotal.toPlainString()),
                staffUserId, now);

        return new Refunded(failed, lineTotal, tender.mode(), tender.customerId(), position, now);
    }

    // ------------------------------------------------------------------
    // Shared mechanics
    // ------------------------------------------------------------------

    /**
     * The recharge, confirmed to be an UNRESOLVED failure.
     *
     * <p>Read without a lock here and locked by
     * {@link RechargeFulfilmentService#resolveFailure} when the status actually
     * moves. The lock has to be taken by the method that writes the status, or
     * the check and the write are not one decision — and taking it twice in one
     * transaction is free.
     */
    private RcRecharge requireFailed(int rechargeId) {
        RcRecharge recharge = recharges.findById(rechargeId)
                .orElseThrow(() -> new RechargeNotFound(rechargeId));
        if (recharge.getStatusCode() != RechargeStatus.FAILED) {
            // resolveFailure raises the more specific
            // FailureAlreadyResolvedException for RETRIED and REFUNDED; this
            // covers AWAITING_RECHARGE, COMPLETED and VOIDED, where the answer
            // is that there is no failure to resolve.
            FailureResolution already = FailureResolution.of(recharge.getStatusCode());
            if (already.isResolved()) {
                throw new FailureAlreadyResolvedException(rechargeId, already);
            }
            throw new IllegalStatusTransitionException(recharge.getStatusCode(),
                    RechargeStatus.RETRIED);
        }
        return recharge;
    }

    /**
     * Refuses a request that settles nothing it should, or something it should
     * not.
     *
     * <p>Both directions are refused rather than one. A missing settlement is
     * §3.2 #12's named 422. An EXTRA one — a cash-back sent with a same-amount
     * retry — is refused too, because silently dropping it would leave the staff
     * member believing money changed hands while the drawer disagrees.
     */
    private void requireSettlementMatches(RetryPlan plan, Settlement difference, Settlement topUp) {
        if (plan.differenceOwed().signum() > 0 && difference == null) {
            throw UnsettledDifferenceException.owed(plan.differenceOwed());
        }
        if (plan.topUpDue().signum() > 0 && topUp == null) {
            throw UnsettledDifferenceException.topUp(plan.topUpDue());
        }
        if (plan.differenceOwed().signum() == 0 && difference != null) {
            throw UnsettledDifferenceException.nothingToSettle("difference");
        }
        if (plan.topUpDue().signum() == 0 && topUp != null) {
            throw UnsettledDifferenceException.nothingToSettle("top-up");
        }
    }

    /**
     * Which ways money may leave {@code RCHG_PENDING} for this sale, and the
     * customer it belongs to (log §7.4, design-api.md:789).
     *
     * <p>⚠ THE AVAILABLE MODES ARE A FACT ABOUT THE ORIGINAL PAYMENT. §7.4: "On
     * a credit original no cash was ever collected, so there is no choice to
     * offer: it can only reduce the tab." And the mirror, which is the rule
     * design-api.md:789 states: a credit reduction is available "only where
     * credit was extended, and only for that line's customer" — reducing a tab
     * that this sale never charged would hand the customer money the shop never
     * took from them.
     *
     * <p>⚠ ONE MODE PER TENDER LINE TYPE, and since {@code KEEP_AS_ADVANCE} was
     * withdrawn on 2026-10-21 there is no longer an unconditional fallback. That
     * is the point of the withdrawal rather than a side effect: the old fallback
     * credited {@code ADV_RECHARGE}, which nothing debits, so a UPI-only sale's
     * only exit left the money owed in a different account forever. {@code UPI}
     * replaced it, and a mixed basket offers one mode per line it carries.
     */
    private Tender resolveResidual(RcRecharge failed, Settlement asked) {
        List<RcTxnPayment> tenders = payments.findByTxnId(failed.getTxnId());
        Optional<RcTxnPayment> creditLine = tenders.stream()
                .filter(t -> SettlementMethod.CREDIT.lineType().equals(t.getLineType()))
                .findFirst();
        boolean cashCollected = tenders.stream()
                .anyMatch(t -> SettlementMethod.CASH.lineType().equals(t.getLineType()));
        boolean upiCollected = tenders.stream()
                .anyMatch(t -> SettlementMethod.UPI.lineType().equals(t.getLineType()));

        List<ResidualMode> available = new ArrayList<>();
        if (cashCollected) {
            available.add(ResidualMode.CASH);
        }
        if (upiCollected) {
            available.add(ResidualMode.UPI);
        }
        if (creditLine.isPresent()) {
            available.add(ResidualMode.CREDIT_REDUCTION);
        }

        ResidualMode mode = asked.mode();
        if (mode == ResidualMode.CASH && !cashCollected) {
            throw new RefundModeNotAvailableException(mode,
                    "no cash was collected on payment P-%d, so there is none to hand back."
                            .formatted(failed.getTxnId()),
                    available);
        }
        if (mode == ResidualMode.UPI && !upiCollected) {
            // The same rule as the other two, in the same shape: the money goes
            // home by a rail it actually arrived on. Refunding by UPI a sale that
            // was paid in cash would move money out of UPI_CLEARING that never
            // came in, and the next UPI reconciliation would be short by it.
            throw new RefundModeNotAvailableException(mode,
                    "payment P-%d had no UPI line, so there is no transfer to send back."
                            .formatted(failed.getTxnId()),
                    available);
        }
        if (mode == ResidualMode.CREDIT_REDUCTION && creditLine.isEmpty()) {
            throw new RefundModeNotAvailableException(mode,
                    "payment P-%d had no credit line, so there is no tab to reduce."
                            .formatted(failed.getTxnId()),
                    available);
        }

        Integer customerId = asked.customerId();
        if (mode == ResidualMode.CREDIT_REDUCTION) {
            // NOT the customer the client sent. §789: "only for that line's
            // customer" — the tab reduced must be the tab that was charged, and
            // letting a request name a different one would move a debt between
            // two people's accounts.
            RcCreditReq charged = creditRequests.findById(creditLine.get().getCreReqId())
                    .orElseThrow(() -> new PaymentNotFound(failed.getTxnId()));
            customerId = charged.getCustId();
        }
        if (mode.needsCustomer() && customerId == null) {
            throw RefundModeNotAvailableException.needsCustomer(mode, available);
        }
        if (mode.needsReference() && (asked.note() == null || asked.note().isBlank())) {
            // The enum owns the rule and this only asks it — review finding 11's
            // lesson, where voidRecharge hardcoded a rule RechargeStatus claimed.
            throw new UpiRequiresReferenceException();
        }
        return new Tender(mode, null, customerId, asked.note());
    }

    /** Which ways the extra on a higher retry may be taken. */
    private Tender resolveTopUp(Settlement asked) {
        SettlementMethod method = asked.method();
        if (method == SettlementMethod.CREDIT && asked.customerId() == null) {
            throw new CreditRequiresCustomerException();
        }
        if (method == SettlementMethod.CREDIT) {
            customers.requireCreditable(asked.customerId());
        }
        return new Tender(null, method, asked.customerId(), asked.note());
    }

    /**
     * Money leaves: one voucher, and a {@code DPAM} row when it reduces a tab.
     *
     * <p>No {@code rc_txn_payment} row is written, and it is not an oversight:
     * {@code ck_txnpay_amount CHECK (AMOUNT > 0)} makes a negative tender line
     * unrepresentable, which is V12 saying that the tender lines record what was
     * COLLECTED rather than the net of a visit. The ledger is where money going
     * out is recorded.
     */
    private Outstanding settleResidual(RcRecharge failed, Tender tender, BigDecimal amount,
                                       String narration, Integer staffUserId,
                                       LocalDateTime now) {
        ledger.postResidual(failed.getRechargeId(), amount, tender.mode(), tender.customerId(),
                staffUserId, narration, tender.note(), now);

        if (tender.mode() == ResidualMode.CREDIT_REDUCTION) {
            // A DPAM row with a POSITIVE req_amt, which is how the append-only
            // tally records anything that reduces the debt. The tab falls by the
            // refund without any row being edited.
            credit.receivePayment(tender.customerId(), amount, staffUserId,
                    narration, failed.getTxnId(), now);
        }
        return tender.customerId() == null ? null : credit.positionOf(tender.customerId());
    }

    /**
     * Money comes in: one voucher, a tender line on the ORIGINAL payment, and
     * the header's total raised to match.
     *
     * <p>⚠ THE TENDER LINE IS WHAT KEEPS THE DAY SUMMARY HONEST. §7.1(c) makes
     * the day summary cross-check two independent sources of the same number —
     * {@code tenderMix} from {@code rc_txn_payment} and {@code cashCollected}
     * from the ledger's {@code CASH_RECHARGE} movement — and says "if they ever
     * differ, a posting is wrong". Money collected into the ledger with no
     * tender line would break that check on the day it happened, so a top-up
     * must appear in both places or in neither.
     *
     * <p>⚠ AND THE HEADER TOTAL MOVES WITH IT. V12's header comment states the
     * invariant the lines carry: "AMOUNT is always POSITIVE and the lines SUM TO
     * {@code txn_total_amt}". Appending a line without raising the total would
     * break it on the one payment shape that can still grow.
     */
    private Outstanding collectTopUp(RcRecharge failed, Tender tender, BigDecimal amount,
                                     String narration, Integer staffUserId, LocalDateTime now) {
        RcTxnHeader header = headers.findById(failed.getTxnId())
                .orElseThrow(() -> new PaymentNotFound(failed.getTxnId()));

        RcCreditReq creditRow = null;
        if (tender.method() == SettlementMethod.CREDIT) {
            creditRow = credit.extendCredit(tender.customerId(), amount, staffUserId,
                    narration, failed.getTxnId(), now);
        }

        short seqNo = (short) (payments.maxSeqNo(failed.getTxnId()) + 1);
        RcTxnPayment line = new RcTxnPayment();
        line.setId(new RcTxnPaymentId(failed.getTxnId(), seqNo));
        line.setLineType(tender.method().lineType());
        line.setAmount(amount);
        line.setReferenceNo(tender.note());
        line.setCreDttm(now);
        if (creditRow != null) {
            line.setCreReqId(creditRow.getId());
            line.setExtendedBy(staffUserId);
            line.setNote(narration);
        }
        payments.saveAndFlush(line);

        header.setTxnTotalAmt(header.getTxnTotalAmt().add(amount));
        if (tender.method() == SettlementMethod.CASH) {
            // AMT_TNDRED is what the customer handed over across the visit. The
            // change already given is unaffected: this is exact money for a
            // known shortfall, not a fresh tender awaiting change.
            header.setAmtTndred(header.getAmtTndred().add(amount));
        }
        if (creditRow != null && header.getCustId() == null) {
            header.setCustId(tender.customerId());
        }
        headers.saveAndFlush(header);

        ledger.postTopUp(failed.getRechargeId(), amount, tender.method(), tender.customerId(),
                staffUserId, narration, now);

        return tender.customerId() == null ? null : credit.positionOf(tender.customerId());
    }

    // ------------------------------------------------------------------
    // What crosses the boundary
    // ------------------------------------------------------------------

    /**
     * How the client says a difference is settled.
     *
     * <p>One record for both directions: {@code mode} is set on the way out and
     * {@code method} on the way in, because the two sets genuinely differ — cash
     * can be kept as an advance only when it is going out, and UPI is only
     * meaningful coming in.
     */
    public record Settlement(ResidualMode mode, SettlementMethod method, Integer customerId,
                             String note) {

        public Settlement(ResidualMode mode, Integer customerId, String note) {
            this(mode, null, customerId, note);
        }
    }

    /** A settlement after the server has decided what it actually is. */
    private record Tender(ResidualMode mode, SettlementMethod method, Integer customerId,
                          String note) {
    }

    /** Everything #12's response needs, materialised inside the transaction. */
    public record Retried(RcRecharge original, RcRecharge retry, RetryPlan plan,
                          ResidualMode differenceSettledAs, SettlementMethod topUpTakenAs,
                          Outstanding creditPosition, LocalDateTime takenAt) {
    }

    /** Everything #13's response needs. */
    public record Refunded(RcRecharge original, BigDecimal amount, ResidualMode mode,
                           Integer customerId, Outstanding creditPosition,
                           LocalDateTime takenAt) {
    }
}
