package com.mangle.retailshopapp.recharge.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The recharge lifecycle, and the only statement of which moves are legal
 * (design-domain.md §5.2, §5.4; log §7.1).
 *
 * <p>These six constants are exactly the {@code ref_recharge_status} codes V9
 * seeds, so the enum and the reference table cannot drift. {@code RETRIED} and
 * {@code REFUNDED} are STATUSES here, not a separate resolution column — see
 * {@link FailureResolution} for how the API's two-field contract is derived from
 * this one field.
 *
 * <p><b>{@code COMPLETED} is not terminal, and {@code FAILED} is its only
 * exit.</b> Log §7.1 added two arrows out of {@code COMPLETED} on 2026-09-29.
 * {@code COMPLETED → FAILED} means the sale was recorded correctly but the
 * operator never delivered, and it is routed deliberately into the existing
 * {@code FAILED → RETRIED | REFUNDED} machinery rather than duplicating it.
 *
 * <p>⚠ {@code COMPLETED → VOIDED} WAS REMOVED ON 2026-10-21, WHICH OVERTURNS
 * §7.1's other half. {@code VOIDED} means the sale should never have been
 * recorded — wrong number, entered twice, never happened — and that is exactly
 * the case where the customer was charged and the money has to go back. A void
 * could not return it. §7.1 said "a void never touches the cash leg … the cash
 * was genuinely collected and is genuinely still held", and then never said how
 * the held money leaves; it assumed {@code FAILED → REFUNDED} would cover it, and
 * that machinery refuses {@code VOIDED}
 * ({@code RechargeFailureResolutionService.requireFailed}). The amount would have
 * stayed in {@code RCHG_PENDING}, on a {@code heldPending} figure with no date
 * filter, for good. A second gap compounded it: {@code COMPLETED} was the only
 * route in, but a double entry is noticed BEFORE the recharges are done, and
 * {@code AWAITING_RECHARGE → VOIDED} was never in this map.
 *
 * <p>{@code COMPLETED → FAILED → REFUNDED} replaces it, posting the identical
 * reversal. ⚠ THE CONSTANT AND {@code isTerminal()} STAY: V9's seeded
 * {@code ref_recharge_status} row is already applied to the developer restore and
 * unseeding it would cost a migration for no gain, so the status stays DEFINED
 * and becomes UNREACHABLE, which is the honest shape. If a {@code VOIDED} row
 * ever appears from any source, the mapping and the screens still render it.
 * Nothing will produce one: {@code #19}'s endpoint is gone, and S6's V15 backfill
 * cannot make one because the legacy tables have no status column at all —
 * {@code rc_txn_header} is {@code TXN_ID, TXN_DTTM, txn_total_amt, amt_tndred,
 * amt_returned} and {@code rc_txn_details} is {@code SEQ_NO, TXN_ID, txn_flg,
 * reference_no, amount, ref_company}.
 *
 * <p>⚠ {@code V12__Recharge_tables.sql:144-145} IS WRONG TWICE OVER, and this is
 * the only place that says so. Its comment reads "COMPLETED → VOIDED and
 * COMPLETED → FAILED both require a reason (log §7.1), and the state machine
 * enforces that". Neither half holds: {@code COMPLETED → VOIDED} is not a
 * transition any more, and {@code COMPLETED → FAILED} takes an OPTIONAL reason,
 * which is what design-api.md §3.2's {@code MarkFailedRequest(reason?)}
 * specifies. The mandatory-reason rule §7.1 attached to {@code VOIDED} alone went
 * with the void path; {@code requiresReasonOnEntry()} was deleted on 2026-10-21
 * once it had no caller, and this note moved here because it was the only thing
 * pointing at V12. V12 is NOT corrected in place: it is already applied to the
 * developer restore, and editing even a comment changes its Flyway checksum,
 * which would fail {@code validate} on every database that has it.
 *
 * <p>Why a status change rather than a ledger-only correction: the day's takings
 * count {@code COMPLETED} rows, so fixing only the accounts would leave the
 * recharge still reading {@code COMPLETED} and the takings still inflated — the
 * ledger and the operational report disagreeing about one sale. Moving the status
 * drops it from takings for free, because the query already filters on
 * {@code COMPLETED}.
 *
 * <p>v2's aggregator adds {@code PENDING} with {@code AWAITING_RECHARGE →
 * PENDING} and {@code PENDING → COMPLETED | FAILED}. That is one constant and
 * two edges in this file, and nothing else changes (§5.6).
 */
public enum RechargeStatus {

    /** Staff has taken the money; the recharge has not been done yet. */
    AWAITING_RECHARGE,

    /** Delivered. Counts toward the day's takings. Not terminal. */
    COMPLETED,

    /**
     * Failed and UNRESOLVED. This is the open state the worklist and the
     * end-of-day warning look for — there is deliberately no separate
     * {@code IS_RESOLVED} flag, because resolving a failure moves it to a
     * terminal status instead (§5.2).
     */
    FAILED,

    /**
     * The sale should not exist. Terminal, and ⚠ UNREACHABLE since 2026-10-21 —
     * nothing transitions to it. Kept because V9 seeds the
     * {@code ref_recharge_status} row and the enum must not drift from it. See the
     * class javadoc for why the path was removed.
     */
    VOIDED,

    /** Resolved by a linked retry. Terminal. */
    RETRIED,

    /** Resolved by cash back or a credit reduction. Terminal. */
    REFUNDED;

    private static final Map<RechargeStatus, Set<RechargeStatus>> ALLOWED = Map.of(
            AWAITING_RECHARGE, EnumSet.of(COMPLETED, FAILED),       // v2: + PENDING
            // log §7.1 added VOIDED here too; it was removed 2026-10-21,
            // leaving VOIDED defined, terminal and unreachable.
            COMPLETED,         EnumSet.of(FAILED),
            FAILED,            EnumSet.of(RETRIED, REFUNDED),
            VOIDED,            EnumSet.noneOf(RechargeStatus.class),
            RETRIED,           EnumSet.noneOf(RechargeStatus.class),
            REFUNDED,          EnumSet.noneOf(RechargeStatus.class));

    /**
     * Whether this status may move to {@code to}.
     *
     * <p>A move to the SAME status is not a transition and returns false. The
     * service decides what that means — §5.3 makes a repeat of the current
     * status a 200 no-op rather than a 409, which is idempotent replay, not a
     * legal transition. Keeping the distinction here means the state machine
     * never has to know about HTTP.
     */
    public boolean canTransitionTo(RechargeStatus to) {
        return to != null && ALLOWED.get(this).contains(to);
    }

    /**
     * Terminal means nothing may follow. It mirrors
     * {@code ref_recharge_status.IS_TERMINAL}, which the open-items query reads
     * instead of listing statuses — so a seventh status needs no query change.
     */
    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    /**
     * Whether a recharge in this status counts toward the day's takings.
     *
     * <p>Only {@code COMPLETED}. Stated as a method rather than left to each
     * caller's {@code == COMPLETED} because it is the rule that makes a
     * correction by status change work at all (§5.2): moving a sale to
     * {@code FAILED} drops it from takings for free.
     */
    public boolean countsTowardTakings() {
        return this == COMPLETED;
    }
}
