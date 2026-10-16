package com.mangle.retailshopapp.recharge.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The whole arithmetic of retrying one failed recharge (design-api.md §5.2,
 * §3.3; log §7.4).
 *
 * <p>Pure, like {@link SettlementPlan}: no database, no mocks, no Spring. §5.4
 * rule 4 is what makes that worth insisting on — the matrix that matters here is
 * arithmetic (same amount, lower, higher, and the boundaries between them), and
 * it should not need a container to run.
 *
 * <p><b>⚠ THE DIFFERENCE IS A RESIDUAL, NOT A POSTING RULE</b> (log §7.4). There
 * is no delta voucher against an earlier one and nothing recomputes an old
 * posting: ₹600 was credited to {@code RCHG_PENDING} at settlement, a retry at
 * ₹450 will consume ₹450 of it at ITS completion, and the ₹150 left over is
 * simply what remains in the account. This type computes what remains; it does
 * not decide how it is posted.
 *
 * <p><b>The surcharge does not move.</b> The shop's own fee was charged for
 * taking the sale, and the operator failing to deliver does not change what the
 * shop did — so the retry carries the ORIGINAL line's surcharge and the whole
 * difference falls on the face value. That also makes the difference equal to
 * the difference in face value, which is the number the staff member typed and
 * the only one they can check. §3.3's worked example reads the same way: ₹219
 * retried at ₹199 owes ₹20.
 *
 * <p><b>Both directions, and only one of them is in §3.3's contract.</b> §3.3
 * shows {@code amount} plus {@code differenceSettlement} and #12's 422 list names
 * only "{@code differenceOwed > 0} with no {@code differenceSettlement}", so a
 * retry at a HIGHER amount has no field to carry the extra. Log §7.4 nonetheless
 * says "the same mechanism covers the opposite direction", replacing E11's delta
 * voucher — so the higher case is supported and the request carries a
 * {@code topUp} that §3.3 does not mention. Recorded as a deliberate extension of
 * the written contract rather than an undocumented accident.
 */
public record RetryPlan(
        BigDecimal originalAmount,
        BigDecimal surcharge,
        BigDecimal retryAmount,
        BigDecimal differenceOwed,
        BigDecimal topUpDue) {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    /**
     * Computes the difference, in whichever direction it falls.
     *
     * <p>Exactly one of {@code differenceOwed} and {@code topUpDue} can be
     * positive; a retry at the same amount leaves both zero and settles nothing,
     * which is the common case and the one that must stay free of ceremony.
     *
     * @param originalAmount the failed recharge's FACE VALUE, excluding surcharge
     * @param surcharge      the failed recharge's surcharge, carried over unchanged
     * @param retryAmount    what the staff member is retrying at
     */
    public static RetryPlan of(BigDecimal originalAmount, BigDecimal surcharge,
                               BigDecimal retryAmount) {
        BigDecimal original = requireScale2(originalAmount, "the original amount");
        BigDecimal fee = requireScale2(surcharge, "the surcharge");
        BigDecimal retry = requireScale2(retryAmount, "the retry amount");

        if (retry.signum() <= 0) {
            // ck_rech_amount CHECK (AMOUNT > 0) says the same in the database.
            // Saying it here gets a sentence instead of a constraint name.
            throw new IllegalArgumentException(
                    "A retry is for a positive amount; got " + retry.toPlainString() + ".");
        }

        BigDecimal delta = original.subtract(retry);
        return new RetryPlan(original, fee, retry,
                delta.signum() > 0 ? delta : ZERO,
                delta.signum() < 0 ? delta.negate() : ZERO);
    }

    /** What the customer paid for the original line: face value plus the fee. */
    public BigDecimal originalLineTotal() {
        return originalAmount.add(surcharge);
    }

    /**
     * What the retry will consume from {@code RCHG_PENDING} at ITS completion.
     *
     * <p>Not consumed now — the retry is created {@code AWAITING_RECHARGE} and
     * stage two posts when it completes, keyed on the new recharge row so it
     * cannot collide with the original's completion (log §7.2).
     */
    public BigDecimal retryLineTotal() {
        return retryAmount.add(surcharge);
    }

    /** Whether anything has to change hands in this call at all. */
    public boolean settlesNothing() {
        return differenceOwed.signum() == 0 && topUpDue.signum() == 0;
    }

    private static BigDecimal requireScale2(BigDecimal v, String what) {
        if (v == null) {
            return ZERO;
        }
        if (v.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(
                    what + " is held to the paisa; got " + v.toPlainString());
        }
        return v.setScale(2, RoundingMode.UNNECESSARY);
    }
}
