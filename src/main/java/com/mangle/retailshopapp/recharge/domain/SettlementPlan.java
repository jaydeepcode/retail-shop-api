package com.mangle.retailshopapp.recharge.domain;

import java.math.BigDecimal;
import java.util.List;

import com.mangle.retailshopapp.recharge.exception.CreditRequiresCustomerException;
import com.mangle.retailshopapp.recharge.exception.InvalidTenderException;
import com.mangle.retailshopapp.recharge.exception.LinesDoNotSumException;
import com.mangle.retailshopapp.recharge.exception.TooManyCreditLinesException;
import com.mangle.retailshopapp.settlement.Amounts;
import com.mangle.retailshopapp.settlement.SettlementLine;
import com.mangle.retailshopapp.settlement.TenderPlan;

/**
 * The whole arithmetic of settling one basket of recharges, and its validation
 * (design-api.md §5.2, §5.3, §4.3 step 1).
 *
 * <p>§5.2 calls this "where the anemia is actually avoided", and §5.4 rule 4 is
 * what makes that claim checkable: pure arithmetic, no database, no mocks, no
 * Spring context, so the settle-basket test matrix — sums, short tender, credit
 * without a customer — needs nothing to run.
 *
 * <p><b>What the server recomputes and the client therefore cannot disagree
 * with</b> (§3.3): the total, the change, and whether the lines balance. The
 * request carries no total, no change and no status. A client that sent one would
 * be sending an opinion about arithmetic it does not own.
 *
 * <p><b>⚠ WHAT IS NO LONGER HERE.</b> How a customer paid is
 * {@link TenderPlan}, in {@code settlement/}, because a ticket is settled the
 * same way against a different basket (next-session-s4.md correction 2). What
 * stays is the half that is genuinely recharge's: what was sold, each line's own
 * surcharge, and the total those two arrive at. The tender rules — the lines
 * add up, at most one credit line, it names somebody, cash covers its own line —
 * are asserted once, over there.
 *
 * <p>The accessors below still read {@code plan.totalDue()} and
 * {@code plan.changeDue()} because a basket's caller should not have to know
 * which half a figure came from.
 *
 * <p>No {@code Money} type (§5.2): {@code BigDecimal} at scale 2 matches
 * {@code decimal(38,2)} and every existing DTO.
 */
public record SettlementPlan(
        List<RechargeLine> recharges,
        TenderPlan tender) {

    /**
     * One recharge in the basket, after the server has filled in what the client
     * may not choose.
     *
     * <p>{@code surcharge} is its OWN amount and is never added into
     * {@code amount}: the two reach different income accounts
     * ({@code INC_RCHG_COMM} and {@code INC_RCHG_SURCHARGE}), and folding them
     * would destroy the only check there is on the operator-paid rate
     * (log §10.2). Keeping it separate is what lets operator commission stay
     * exactly {@code A − round(A × (1 − r), 2)} and remain checkable against the
     * rate card and the portal debit.
     *
     * <p>⚠ This is the half that could NOT be shared. A ticket has no surcharge
     * and reaches its total as {@code fareAmount + commissionAmount}, so a
     * ticket settling through {@code RechargeLine} would be carrying a field
     * that means nothing to it.
     */
    public record RechargeLine(
            ServiceType serviceType,
            String number,
            String operatorCode,
            String routeCode,
            BigDecimal amount,
            BigDecimal surcharge) {

        public RechargeLine {
            amount = Amounts.requireScale2(amount, "amount");
            surcharge = Amounts.requireScale2(surcharge, "surcharge");
        }

        /** What the customer pays for this line. */
        public BigDecimal lineTotal() {
            return amount.add(surcharge);
        }
    }

    /**
     * Validates the basket and computes everything derived.
     *
     * <p>Ordered so the cheapest rejections happen first and so each message
     * names one thing — §4.3 step 1 is explicit that this runs before ANY I/O, so
     * a malformed basket costs no database round trip at all.
     *
     * @throws LinesDoNotSumException        the lines do not match the recomputed total
     * @throws CreditRequiresCustomerException a credit line with no customer
     * @throws TooManyCreditLinesException   more than one credit line
     * @throws InvalidTenderException        tender on a non-cash line, or short of its own line
     */
    public static SettlementPlan of(List<RechargeLine> recharges,
                                    List<SettlementLine> settlementLines) {
        if (recharges == null || recharges.isEmpty()) {
            throw new InvalidTenderException("A basket needs at least one recharge.");
        }

        for (RechargeLine line : recharges) {
            // ck_rech_amount CHECK (AMOUNT > 0) says the same thing in the
            // database; saying it here first gets a sentence instead of a
            // constraint name.
            if (line.amount().signum() <= 0) {
                throw new InvalidTenderException(
                        "A recharge amount must be more than zero; got "
                                + line.amount().toPlainString() + ".");
            }
            if (line.surcharge().signum() < 0) {
                throw new InvalidTenderException("A surcharge cannot be negative.");
            }
        }

        // THE total. Recomputed from the recharges, including each line's own
        // surcharge, so there is exactly one authority for it — and then handed
        // to the tender as the figure it has to match.
        BigDecimal totalDue = recharges.stream()
                .map(RechargeLine::lineTotal)
                .reduce(Amounts.ZERO, BigDecimal::add);

        return new SettlementPlan(List.copyOf(recharges), TenderPlan.of(settlementLines, totalDue));
    }

    // -- the tender half, read through the basket ---------------------------

    public List<SettlementLine> settlementLines() {
        return tender.lines();
    }

    public BigDecimal totalDue() {
        return tender.totalDue();
    }

    public BigDecimal cashTendered() {
        return tender.cashTendered();
    }

    public BigDecimal changeDue() {
        return tender.changeDue();
    }

    /** The credit line, if the basket has one. */
    public SettlementLine creditLine() {
        return tender.creditLine();
    }

    public boolean hasCredit() {
        return tender.hasCredit();
    }

    /** Whether the change should be shown at the counter (log §14.5). */
    public boolean changeIsDisplayed(BigDecimal displayThreshold) {
        return tender.changeIsDisplayed(displayThreshold);
    }

    // -- the basket half, which is recharge's alone -------------------------

    /**
     * What the shop's own surcharge comes to across the basket.
     *
     * <p>Its own figure because it is its own voucher leg: {@code Cr
     * INC_RCHG_SURCHARGE} is computed directly rather than by subtraction, which
     * is what keeps operator commission checkable against the rate card
     * (log §15.11).
     */
    public BigDecimal totalSurcharge() {
        return recharges.stream()
                .map(RechargeLine::surcharge)
                .reduce(Amounts.ZERO, BigDecimal::add);
    }

    /** The recharge face value, surcharge excluded. */
    public BigDecimal totalRechargeAmount() {
        return recharges.stream()
                .map(RechargeLine::amount)
                .reduce(Amounts.ZERO, BigDecimal::add);
    }
}
