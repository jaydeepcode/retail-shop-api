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
 * <p><b>{@code COMPLETED} is not terminal; {@code VOIDED} is.</b> Log §7.1 added
 * both arrows out of {@code COMPLETED} on 2026-09-29, and they differ in kind on
 * purpose: {@code VOIDED} means the sale should never have been recorded (wrong
 * number, entered twice, never happened), while {@code FAILED} means it was
 * recorded correctly but the operator never delivered — routed deliberately into
 * the existing {@code FAILED → RETRIED | REFUNDED} machinery rather than
 * duplicating it.
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

    /** The sale should not exist. Terminal. */
    VOIDED,

    /** Resolved by a linked retry. Terminal. */
    RETRIED,

    /** Resolved by cash back or a credit reduction. Terminal. */
    REFUNDED;

    private static final Map<RechargeStatus, Set<RechargeStatus>> ALLOWED = Map.of(
            AWAITING_RECHARGE, EnumSet.of(COMPLETED, FAILED),       // v2: + PENDING
            COMPLETED,         EnumSet.of(VOIDED, FAILED),          // log §7.1 — both new
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
     * caller's {@code == COMPLETED} because it is the rule that makes
     * {@code VOIDED} work at all (§5.2).
     */
    public boolean countsTowardTakings() {
        return this == COMPLETED;
    }

    /**
     * Whether entering this status mandates a reason.
     *
     * <p>⚠ ONE STATUS, NOT TWO, and the javadoc here used to say two while the
     * body said one. log §7.1 attaches the requirement to {@code VOIDED} alone —
     * "one new terminal status, reachable only from {@code COMPLETED}, with a
     * mandatory reason" — and the argument is specific to it: a void "must not
     * become a back door for refunding a delivered recharge", because reversing
     * the float legs would restore float that never came back, and with v1 fixed
     * as single-user (FRD §3) there is no second approver. The reason plus float
     * reconciliation IS the control.
     *
     * <p>{@code COMPLETED → FAILED} takes an OPTIONAL reason, which is what
     * design-api.md §3.2's {@code MarkFailedRequest(reason?)} specifies. It is a
     * different kind of event: the operator failed to deliver, which is ordinary,
     * rather than a sale being withdrawn.
     *
     * <p>⚠ {@code V12__Recharge_tables.sql} states the opposite — "COMPLETED →
     * VOIDED and COMPLETED → FAILED both require a reason (log §7.1), and the
     * state machine enforces that" — and that comment is wrong. It is NOT
     * corrected in place: V12 is already applied to the developer restore, and
     * editing even a comment changes its Flyway checksum, which would fail
     * {@code validate} on every database that has it. Recorded here, where the
     * rule actually lives.
     */
    public boolean requiresReasonOnEntry() {
        return this == VOIDED;
    }
}
