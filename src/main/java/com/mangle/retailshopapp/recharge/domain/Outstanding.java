package com.mangle.retailshopapp.recharge.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * What one customer's tab comes to, normalised (design-api.md §5.2).
 *
 * <p>This is the ONE place {@code rc_credit_req}'s sign convention is turned into
 * a number a screen can show, and that is the whole reason the type exists.
 * {@code DBT} rows are stored NEGATIVE and {@code DPAM} rows positive, so
 * {@code SUM(REQ_AMT)} is negative for someone who owes money. §1.8 requires that
 * {@code outstanding} always be "a positive amount owed, whatever
 * {@code SUM(req_amt)}'s sign is".
 *
 * <p>⚠ TWO CUSTOMERS ARE IN CREDIT and must survive this normalisation: +₹37 and
 * +₹15 (log §2.8, §14.2). design-domain.md §7.1(b) warns that a "who owes us"
 * screen filtering {@code balance < 0} "silently loses them", and §14.2 records
 * the same ₹52 being counted twice in opposite directions in a sealed opening
 * journal because the net was carried away from its condition. So this type keeps
 * BOTH facts — an amount owed and an amount held — and never collapses them into
 * one signed number.
 *
 * <p>The tab is shown as one netted position: "owes ₹120" or "has ₹80 with us",
 * never as two numbers (log §2.8).
 *
 * <p>No {@code Money} type: §5.2 rules it out as over-engineering.
 * {@code BigDecimal} at scale 2 matches {@code decimal(38,2)} in the schema and
 * every existing DTO.
 */
public record Outstanding(BigDecimal owed, BigDecimal advanceHeld, LocalDateTime oldestOpenAt) {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    public Outstanding {
        owed = requireScale2(owed);
        advanceHeld = requireScale2(advanceHeld);
        if (owed.signum() > 0 && advanceHeld.signum() > 0) {
            // A netted tally cannot be both at once. If it ever is, the caller
            // has summed two things rather than one, and a loud failure here is
            // better than a credit book that shows a debt and a credit for the
            // same person.
            throw new IllegalArgumentException(
                    "a tab is owed OR held, never both: owed=" + owed + " held=" + advanceHeld);
        }
    }

    /**
     * Builds from the raw {@code SUM(REQ_AMT)}, negative-is-debt.
     *
     * @param tally        {@code SUM(REQ_AMT)}; negative means the customer owes
     * @param oldestOpenAt when the currently-open debt began, or null when nothing is open
     */
    public static Outstanding fromTally(BigDecimal tally, LocalDateTime oldestOpenAt) {
        BigDecimal t = requireScale2(tally);
        if (t.signum() < 0) {
            return new Outstanding(t.negate(), ZERO, oldestOpenAt);
        }
        // Zero lands here and is correct: nothing owed, nothing held, and no
        // open debt to age.
        return new Outstanding(ZERO, t, null);
    }

    public static Outstanding clear() {
        return new Outstanding(ZERO, ZERO, null);
    }

    /** True when money is owed to the shop. */
    public boolean owes() {
        return owed.signum() > 0;
    }

    /** True when the customer has money with the shop (log §2.8's two cases). */
    public boolean inCredit() {
        return advanceHeld.signum() > 0;
    }

    /**
     * The single netted figure, positive when owed and negative when held.
     *
     * <p>For arithmetic and for the ledger, not for a screen — the screen wants
     * {@link #owes()} plus one of the two amounts, so that "has ₹80 with us"
     * reads as a sentence rather than as a minus sign.
     */
    public BigDecimal netPosition() {
        return owed.subtract(advanceHeld);
    }

    /**
     * How long the currently-open debt has been open, in days, or {@code null}
     * when nothing is open.
     *
     * <p>"Oldest debt" is the date the CURRENTLY outstanding debt began, not the
     * first charge ever: with a single-sided tally and no per-charge allocation,
     * that is FIFO consumption — the last moment the running balance was clear
     * (design-domain.md §7.1b). Computing it is the query's job; this type only
     * ages what the query found.
     */
    public Integer ageInDays(LocalDate today) {
        if (oldestOpenAt == null || !owes()) {
            return null;
        }
        return (int) ChronoUnit.DAYS.between(oldestOpenAt.toLocalDate(), today);
    }

    /**
     * The 30-day flag, from {@code rc_setting['credit.warning.days']}.
     *
     * <p>Computed here so two clients cannot disagree about it (§3.3).
     *
     * <p>Worth knowing before the red badge ships: design-domain.md §7.1(b)
     * measured that 33 of 34 open balances will be flagged on day one, because
     * their last activity is 2019-2025. That is the data, not a bug.
     */
    public boolean warn(LocalDate today, int thresholdDays) {
        Integer age = ageInDays(today);
        return age != null && age >= thresholdDays;
    }

    /**
     * Applies a charge at {@code when}, consuming any advance first.
     *
     * <p>⚠ THE MIRROR OF log §2.8's RULE, which the log states in one direction
     * only. §2.8 establishes that a RECEIPT clears the balance and the remainder
     * becomes an advance; this is the other half — a CHARGE against a named
     * customer consumes the advance first and only then becomes debt. That is
     * what lets the two customers holding +₹37 and +₹15 actually spend it, with
     * no fourth payment method and no customer identification added to otherwise
     * anonymous sales.
     *
     * <p>{@code when} is needed rather than inferable: a charge against a CLEAR
     * tab starts the ageing clock, and a charge against an already-open tab must
     * leave it alone, because the 30-day warning is about the oldest open debt
     * and not the newest charge.
     */
    public Outstanding charge(BigDecimal amount, LocalDateTime when) {
        BigDecimal charged = requireScale2(amount);
        if (charged.signum() <= 0) {
            throw new IllegalArgumentException("a charge must be positive: " + charged);
        }
        BigDecimal consumedFromAdvance = advanceHeld.min(charged);
        BigDecimal newAdvance = advanceHeld.subtract(consumedFromAdvance);
        BigDecimal newOwed = owed.add(charged.subtract(consumedFromAdvance));

        if (newOwed.signum() == 0) {
            return new Outstanding(ZERO, newAdvance, null);
        }
        // Already open: keep the original date, so a part payment followed by a
        // new charge does not reset the clock. Newly open: this charge is the
        // oldest open debt there is.
        return new Outstanding(newOwed, ZERO, owes() ? oldestOpenAt : when);
    }

    /**
     * Applies a receipt, clearing the balance oldest-first and holding any
     * surplus as an advance (log §2.8).
     *
     * <p>"Exceeds outstanding" is deliberately NOT an error: §2.8 is the case
     * {@code ADV_*} was built for — ₹120 owed, the customer hands over ₹200, ₹80
     * is held against future trips, "decided by arithmetic rather than by asking
     * the staff member". design-api.md §3.2 removed the 422 that used to refuse
     * it. Only a non-positive amount is rejected.
     *
     * <p>There is no per-charge allocation and no business picker: a repayment
     * clears oldest first across the WHOLE tab, every business included (log
     * §14.8). The {@code AR_*} accounts stay split in the ledger so debt remains
     * attributable, but no screen shows that split.
     */
    public Outstanding receive(BigDecimal amount) {
        BigDecimal received = requireScale2(amount);
        if (received.signum() <= 0) {
            throw new IllegalArgumentException("a receipt must be positive: " + received);
        }
        BigDecimal remaining = owed.subtract(received);
        if (remaining.signum() > 0) {
            // Still owing, so the open-debt date is unchanged — a part payment
            // does not reset the ageing clock, which is the point of FIFO.
            return new Outstanding(remaining, advanceHeld, oldestOpenAt);
        }
        return new Outstanding(ZERO, advanceHeld.add(remaining.negate()), null);
    }

    /**
     * Null becomes zero; anything finer than a paisa is a programming error
     * rather than something to round away.
     *
     * <p>Rounding silently here would be the wrong kind of forgiving: the figures
     * come either from {@code decimal(38,2)} columns, which cannot carry a third
     * decimal, or from a request already constrained by
     * {@code @Digits(fraction = 2)}. A third decimal therefore means a caller has
     * computed something it should have asked the ledger for, and
     * {@code RoundingMode.UNNECESSARY}'s bare {@code ArithmeticException} would
     * not say so.
     */
    private static BigDecimal requireScale2(BigDecimal v) {
        if (v == null) {
            return ZERO;
        }
        if (v.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(
                    "money is held to the paisa; got " + v.toPlainString());
        }
        return v.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }
}
