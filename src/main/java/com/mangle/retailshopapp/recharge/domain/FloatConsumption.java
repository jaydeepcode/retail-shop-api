package com.mangle.retailshopapp.recharge.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What one completed recharge costs the shop, and what it earns
 * (design-ledger.md §3.3).
 *
 * <p>ONE formula for both funding patterns, which is why {@code FLOAT_PATTERN =
 * 'BOTH'} is a legal value rather than a special case:
 *
 * <pre>
 * faceConsumed = round(A  × (1 − r), 2)      -- what the operator portal will debit
 * costConsumed = round(faceConsumed × WA, 2) -- what that face actually cost us
 * commission   = A − costConsumed            -- exact, BY SUBTRACTION
 * </pre>
 *
 * <p><b>⚠ COMMISSION IS COMPUTED BY SUBTRACTION, NEVER AS {@code A × r} ROUNDED
 * SEPARATELY.</b> That is the single most important line in this class. §3.3:
 * computing it independently "is what makes the voucher balance to the paisa with
 * no plug figure". Round {@code A × r} on its own and the three legs of the
 * income voucher no longer add up, so either the voucher is refused by V23's
 * trigger or someone adds a rounding line — and a rounding line in the book of
 * record is indistinguishable from an error.
 *
 * <p><b>{@code WA} is 1.0 for all four wallets today, and the machinery is
 * dormant rather than dead.</b> All four are FRD §7.2 pattern 2 — pay ₹X receive
 * ₹X — so cost equals face and the ratio is exactly 1.0 (log §2.4, §8.0). §3.5
 * says the weighted-average machinery must not be deleted and must not be
 * presented as day-one work: it is what makes a bonus-on-topup operator a
 * configuration change rather than a redesign.
 *
 * <p>Keeping {@code WA} at 1.0 is also what makes float reconcilable: the books
 * should track the operator portal rupee for rupee, so a gap is usually one whole
 * unrecorded top-up rather than an accumulation of rounding (log §14.9).
 */
public record FloatConsumption(
        BigDecimal collected,
        BigDecimal rate,
        BigDecimal weightedAverageCost,
        BigDecimal faceConsumed,
        BigDecimal costConsumed,
        BigDecimal commission) {

    private static final BigDecimal ONE = BigDecimal.ONE;

    /**
     * @param collected          {@code A} — the amount collected from the customer,
     *                           the recharge face value and NOT including the
     *                           shop's own surcharge
     * @param rate               {@code r} — the commission rate, 0.036 for 3.6%;
     *                           zero for a bonus-on-topup operator
     * @param weightedAverageCost {@code WA} — {@code SUM(COST_DELTA) / SUM(FACE_DELTA)},
     *                           1.0 for every wallet the shop holds today
     */
    public static FloatConsumption of(BigDecimal collected, BigDecimal rate,
                                      BigDecimal weightedAverageCost) {
        if (collected == null || collected.signum() <= 0) {
            throw new IllegalArgumentException("a recharge amount must be positive: " + collected);
        }
        if (rate == null || rate.signum() < 0 || rate.compareTo(ONE) >= 0) {
            // A rate of 1 would mean the operator funds the whole recharge, and
            // above 1 the float would go UP on a sale. Both are configuration
            // errors worth refusing loudly rather than posting.
            throw new IllegalArgumentException("a commission rate must be in [0, 1): " + rate);
        }
        if (weightedAverageCost == null || weightedAverageCost.signum() <= 0) {
            throw new IllegalArgumentException(
                    "a weighted average cost must be positive: " + weightedAverageCost);
        }

        BigDecimal face = collected.multiply(ONE.subtract(rate))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal cost = face.multiply(weightedAverageCost)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal commission = collected.subtract(cost);

        return new FloatConsumption(collected, rate, weightedAverageCost, face, cost, commission);
    }

    /**
     * The common case: pattern 2, so {@code WA = 1.0}.
     *
     * <p>Named rather than defaulted, so a caller that has not thought about the
     * weighted average has to say so.
     */
    public static FloatConsumption atParity(BigDecimal collected, BigDecimal rate) {
        return of(collected, rate, ONE.setScale(8));
    }

    /**
     * Sanity check on the identity the voucher depends on.
     *
     * <p>{@code collected = costConsumed + commission} must hold exactly, because
     * those are the credit legs against one debit of {@code collected}. It cannot
     * fail given subtraction — which is the point, and is worth asserting in a
     * test rather than trusting.
     */
    public boolean balances() {
        return collected.compareTo(costConsumed.add(commission)) == 0;
    }
}
