package com.mangle.retailshopapp.recharge.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mangle.retailshopapp.recharge.domain.RechargeStatus;
import com.mangle.retailshopapp.recharge.exception.IllegalStatusTransitionException;
import com.mangle.retailshopapp.recharge.exception.ReasonRequiredException;
import com.mangle.retailshopapp.recharge.exception.RechargeNotFound;
import com.mangle.retailshopapp.recharge.model.RcRecharge;
import com.mangle.retailshopapp.recharge.model.RcRechargeStatusHist;
import com.mangle.retailshopapp.recharge.repo.RcRechargeRepository;
import com.mangle.retailshopapp.recharge.repo.RcRechargeStatusHistRepository;

/**
 * Doing the recharges, and the ledger post that follows
 * (design-api.md §5.1, design-domain.md §5.4).
 *
 * <p>Every mutation goes through {@link #transition}, which inside one
 * transaction re-reads the row with {@code PESSIMISTIC_WRITE}, asserts the move
 * is legal, writes the status, appends a history row, asserts the one invariant
 * MySQL cannot hold, and posts to the ledger where the transition earns income.
 *
 * <p><b>⚠ THE LOCKING STRATEGY RESOLVES A THREE-WAY CONTRADICTION, and it is
 * worth recording which way and why.</b>
 *
 * <ul>
 *   <li>design-api.md §7's threat table (row 4) proposes a {@code @Version}
 *       column and optimistic locking, and §3.3's response carries a
 *       {@code version} field.
 *   <li>design-api.md §7.2 then lists "{@code SELECT … FOR UPDATE} anywhere"
 *       among the things rejected as over-engineering.
 *   <li>design-domain.md §5.4 step 1 specifies {@code PESSIMISTIC_WRITE}
 *       outright, "because two staff on the counter can hit the same failed
 *       recharge".
 * </ul>
 *
 * <p>Two later documents settle it toward pessimistic. log §8.1 (2026-09-30)
 * moves the float availability check and its {@code SELECT … FOR UPDATE} to the
 * completion moment, and design-ledger.md §5.2 says the transition "already takes
 * {@code PESSIMISTIC_WRITE} on the recharge row … The existing pessimistic lock
 * also serialises concurrent completion attempts for free." And decisively,
 * {@code rc_recharge} HAS NO {@code VERSION} COLUMN — V12 never created one — so
 * the optimistic form is not merely disfavoured, it is unimplemented. One lock,
 * doing both jobs.
 *
 * <p><b>A repeat of the current status is a 200 no-op, not a conflict</b>
 * (§5.3, and the same pattern as {@code WaterTransactionService:357-361}). §7's
 * threat 2 calls this "worth it, but free": making the transition naturally
 * idempotent is why completion needs no idempotency key.
 */
@Service
public class RechargeFulfilmentService {

    /** The statuses a recharge can still be worked on from. */
    private static final List<RechargeStatus> OPEN_STATUSES = List.of(
            RechargeStatus.AWAITING_RECHARGE, RechargeStatus.COMPLETED, RechargeStatus.FAILED);

    private final RcRechargeRepository recharges;
    private final RcRechargeStatusHistRepository history;
    private final OperatorConfigService config;
    private final RechargeLedgerPostings ledger;
    private final Clock clock;

    public RechargeFulfilmentService(RcRechargeRepository recharges,
                                     RcRechargeStatusHistRepository history,
                                     OperatorConfigService config,
                                     RechargeLedgerPostings ledger,
                                     Clock clock) {
        this.recharges = recharges;
        this.history = history;
        this.config = config;
        this.ledger = ledger;
        this.clock = clock;
    }

    /**
     * Marks a recharge done — the moment it counts toward the day's takings and
     * the moment the float is consumed (§3.2 endpoint #9).
     *
     * @param routeCode optional override. Captured HERE rather than at settlement
     *                  because at the moment the customer pays nobody yet knows
     *                  which connection will be used (log §14.6). Measured
     *                  frequency in FY2026-27: two recharges, both on 2026-08-20
     */
    @Transactional
    public RcRecharge markCompleted(long rechargeId, String vendorRef, String routeCode,
                                    Integer staffUserId) {
        RcRecharge recharge = lockOrThrow(rechargeId);

        if (recharge.getStatusCode() == RechargeStatus.COMPLETED) {
            // Idempotent replay, not a conflict. Nothing is written and nothing
            // is posted twice — the ledger would refuse the second post anyway,
            // but returning early means a double-click does not even try.
            return recharge;
        }
        assertLegal(recharge, RechargeStatus.COMPLETED);

        if (routeCode != null && !routeCode.equals(recharge.getRouteCode())) {
            // An OVERRIDE is a new choice being made now, so it must be a route
            // the shop still uses — unlike the recharge's existing route, which
            // only has to have been valid when the sale was taken.
            config.requireActiveRoute(routeCode);
            recharge.setRouteCode(routeCode);
        }
        if (vendorRef != null && !vendorRef.isBlank()) {
            recharge.setExternalRef(vendorRef.trim());
        }

        LocalDateTime now = LocalDateTime.now(clock);
        apply(recharge, RechargeStatus.COMPLETED, staffUserId, null, now);

        // Stage two of the posting (log §7.2). Inside this transaction, and the
        // pessimistic lock taken above is what serialises two terminals
        // completing the same recharge.
        //
        // ⚠ requireKnown, NOT requireSelectable, and the difference traps money.
        // requireSelectable applies the PICKER's rule — active, not a sentinel —
        // which is right for a new sale and wrong for one already taken. A
        // recharge settled before its operator or route was retired would
        // otherwise be uncompletable forever, with its cash stranded in
        // RCHG_PENDING and no exit but a refund this slice has not built. V20
        // retired IDEA and VDFN mid-project and V16 will retire SARAVATE, so this
        // is a scheduled event rather than a hypothetical.
        //
        // The operator is read only for its display name in the rate-refusal
        // message, so the stricter check bought nothing here even when it passed.
        var operator = config.requireKnown(recharge.getOperatorCode());
        var route = config.requireKnownRoute(recharge.getRouteCode());
        ledger.postCompletion(recharge, operator, route, staffUserId, now);

        return recharge;
    }

    /**
     * Marks a recharge failed (§3.2 endpoint #10).
     *
     * <p>⚠ WHAT POSTS DEPENDS ON WHERE IT CAME FROM, and conflating the two was
     * a defect. From {@code AWAITING_RECHARGE}, NOTHING posts — log §7.2's single
     * biggest saving: "the money simply stays in {@code RCHG_PENDING} … the
     * failure path is the common one, and it becomes free."
     *
     * <p>But the transition is also reachable from {@code COMPLETED} (log §7.1):
     * a recharge marked complete that the operator never delivered is routed into
     * the existing {@code FAILED → RETRIED | REFUNDED} machinery rather than
     * duplicating it. On THAT path stage two has already run, so the completion
     * voucher is reversed — otherwise {@code INC_RCHG_COMM} and the float
     * consumption would stay recognised for a recharge that did not happen, while
     * the day summary dropped the sale from takings and reported nothing held.
     */
    @Transactional
    public RcRecharge markFailed(long rechargeId, String reason, Integer staffUserId) {
        RcRecharge recharge = lockOrThrow(rechargeId);

        if (recharge.getStatusCode() == RechargeStatus.FAILED) {
            return recharge;
        }
        assertLegal(recharge, RechargeStatus.FAILED);

        LocalDateTime now = LocalDateTime.now(clock);
        boolean wasCompleted = recharge.getStatusCode() == RechargeStatus.COMPLETED;

        if (reason != null && !reason.isBlank()) {
            recharge.setFailureReason(reason.trim());
        }
        apply(recharge, RechargeStatus.FAILED, staffUserId, reason, now);

        // ⚠ FROM COMPLETED, THE INCOME HAS ALREADY BEEN RECOGNISED and has to be
        // taken back. From AWAITING_RECHARGE nothing was posted, so this is a
        // no-op — which is log §7.2's biggest saving and still holds for the
        // common path. The two cases are not interchangeable, and the javadoc
        // above used to claim the second for both.
        if (wasCompleted) {
            ledger.reverseCompletion(recharge, staffUserId,
                    reason == null || reason.isBlank() ? "marked failed after completion" : reason,
                    now);
        }
        return recharge;
    }

    /**
     * Voids a completed sale — the sale should not exist (§3.2 endpoint #19).
     *
     * <p>⚠ THE MANDATORY REASON IS THE CONTROL, not paperwork. log §7.1: a void
     * must not become a back door for refunding a DELIVERED recharge, because
     * reversing the float legs would restore float that never came back and the
     * next portal reconciliation would show the gap. There is no second approver,
     * because FRD §3 fixes v1 as single-user — so the reason plus float
     * reconciliation is all there is.
     *
     * <p>⚠ THE CASH LEG IS UNTOUCHED. §3.2: "a void never touches money the shop
     * still holds." The cash was genuinely collected and is genuinely still in the
     * drawer, so only the COMPLETION voucher is reversed and the amount returns to
     * {@code RCHG_PENDING}, from where §7.4's residual mechanism settles it.
     *
     * <p>⚠ THE REVERSAL IS NOW POSTED, which it was not when this slice was first
     * written. A status change alone left the ledger overstating income, because
     * the status drops the sale out of takings while the completion voucher stays.
     * {@link RechargeLedgerPostings#reverseCompletion} writes a dated reversing
     * JOURNAL — the form FRD §8.3 mandates after a reconciliation point and which
     * is correct before one too, so it does not wait on
     * {@code LedgerCorrectionRules} choosing between a journal and a
     * delete-and-repost.
     */
    @Transactional
    public RcRecharge voidRecharge(long rechargeId, String reason, Integer staffUserId) {
        if (reason == null || reason.isBlank()) {
            throw new ReasonRequiredException("void a recharge");
        }
        RcRecharge recharge = lockOrThrow(rechargeId);
        assertLegal(recharge, RechargeStatus.VOIDED);

        LocalDateTime now = LocalDateTime.now(clock);
        apply(recharge, RechargeStatus.VOIDED, staffUserId, reason.trim(), now);

        // VOIDED is reachable only from COMPLETED, so there is always a
        // completion voucher to take back.
        ledger.reverseCompletion(recharge, staffUserId, reason.trim(), now);
        return recharge;
    }

    /**
     * Re-reads the row under a write lock (design-domain.md §5.4 step 1).
     *
     * <p>A plain read-then-write is genuinely racy under {@code REPEATABLE READ}:
     * §7's threat 4 describes it exactly — both transactions read
     * {@code AWAITING_RECHARGE} from their own snapshot, both pass the legality
     * check, and the second {@code UPDATE} wins silently. The lock is what makes
     * the check and the write one decision.
     */
    private RcRecharge lockOrThrow(long rechargeId) {
        return recharges.findByIdForUpdate(rechargeId)
                .orElseThrow(() -> new RechargeNotFound(rechargeId));
    }

    private void assertLegal(RcRecharge recharge, RechargeStatus to) {
        if (!recharge.getStatusCode().canTransitionTo(to)) {
            throw new IllegalStatusTransitionException(recharge.getStatusCode(), to);
        }
    }

    /**
     * Writes the status, stamps who and when, and appends the history row — all
     * in the caller's transaction (§5.4 steps 3-5).
     */
    private void apply(RcRecharge recharge, RechargeStatus to, Integer staffUserId,
                       String reason, LocalDateTime now) {
        RechargeStatus from = recharge.getStatusCode();

        recharge.setStatusCode(to);
        recharge.setStatusChangedAt(now);
        recharge.setStatusChangedBy(staffUserId);
        recharges.saveAndFlush(recharge);

        history.save(RcRechargeStatusHist.of(recharge.getRechargeId(), from, to,
                staffUserId, reason, now));

        // §5.4 step 5: the §2.2(3) rule is the one invariant MySQL cannot hold,
        // because it spans rows and CHECK cannot see other rows. At most one
        // non-terminal attempt may exist per item line, or a retry chain could
        // leave two live recharges against one sale.
        if (!to.isTerminal()) {
            long open = recharges.countOpenAttemptsOnLine(
                    recharge.getTxnId(), recharge.getSeqNo(), OPEN_STATUSES);
            if (open > 1) {
                throw new IllegalStateException(
                        "line %d/%d now has %d open attempts; at most one is allowed (§2.2(3))"
                                .formatted(recharge.getTxnId(), recharge.getSeqNo(), open));
            }
        }
    }
}
