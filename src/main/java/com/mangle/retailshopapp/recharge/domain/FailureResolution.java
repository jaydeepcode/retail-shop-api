package com.mangle.retailshopapp.recharge.domain;

/**
 * The API's {@code resolution} field — DERIVED from {@link RechargeStatus},
 * never stored.
 *
 * <p>⚠ THIS RESOLVES A CONTRADICTION BETWEEN TWO DESIGN DOCUMENTS, and the
 * direction is worth recording because the API contract reads as though a column
 * exists. design-api.md §3.3 presents {@code status} and {@code resolution} as
 * two independent fields and §3.2's endpoint #11 filters on
 * "{@code status=FAILED, resolution=NONE}". The schema takes the other model:
 * design-domain.md §5.2 says "{@code FAILED} is deliberately the open state —
 * 'unresolved failures' is then {@code STATUS_CODE='FAILED'}, no extra
 * {@code IS_RESOLVED} flag", and V9 seeds {@code RETRIED} and {@code REFUNDED}
 * as statuses with {@code IS_TERMINAL=TRUE}. There is no {@code RESOLUTION}
 * column in {@code rc_recharge} and never was.
 *
 * <p><b>Derived rather than added</b>, because the one-field form is the better
 * model and the two-field form is the better contract, and they map cleanly:
 *
 * <table border="1">
 *   <caption>The mapping §3.3's table implies</caption>
 *   <tr><th>{@code STATUS_CODE}</th><th>wire {@code status}</th><th>wire {@code resolution}</th></tr>
 *   <tr><td>AWAITING_RECHARGE</td><td>AWAITING_RECHARGE</td><td>NONE</td></tr>
 *   <tr><td>COMPLETED</td><td>COMPLETED</td><td>NONE</td></tr>
 *   <tr><td>VOIDED</td><td>VOIDED</td><td>NONE</td></tr>
 *   <tr><td>FAILED</td><td>FAILED</td><td>NONE</td></tr>
 *   <tr><td>RETRIED</td><td>FAILED</td><td>RETRIED</td></tr>
 *   <tr><td>REFUNDED</td><td>FAILED</td><td>REFUNDED</td></tr>
 * </table>
 *
 * <p>Adding the column instead would have put the same fact in two places and
 * created a fourth way for them to disagree — a row reading
 * {@code (FAILED, RETRIED)} and a row reading {@code (RETRIED, NONE)} would both
 * be representable, and {@code IS_TERMINAL} would stop being trustworthy.
 * Deriving makes the contract a view of one fact.
 */
public enum FailureResolution {

    /** Not resolved — or not a failure at all. */
    NONE,

    /** Resolved by a linked retry. */
    RETRIED,

    /** Resolved by cash back or a credit reduction. */
    REFUNDED;

    /**
     * The {@code resolution} to put on the wire for a stored status.
     */
    public static FailureResolution of(RechargeStatus status) {
        return switch (status) {
            case RETRIED -> RETRIED;
            case REFUNDED -> REFUNDED;
            case AWAITING_RECHARGE, COMPLETED, FAILED, VOIDED -> NONE;
        };
    }

    /**
     * The {@code status} to put on the wire for a stored status: the resolved
     * statuses surface as {@code FAILED} carrying a resolution, per §3.3's table.
     */
    public static RechargeStatus wireStatus(RechargeStatus status) {
        return switch (status) {
            case RETRIED, REFUNDED -> RechargeStatus.FAILED;
            case AWAITING_RECHARGE, COMPLETED, FAILED, VOIDED -> status;
        };
    }

    public boolean isResolved() {
        return this != NONE;
    }
}
