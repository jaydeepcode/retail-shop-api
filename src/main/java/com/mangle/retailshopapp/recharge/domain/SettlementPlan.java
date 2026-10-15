package com.mangle.retailshopapp.recharge.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import com.mangle.retailshopapp.recharge.exception.CreditRequiresCustomerException;
import com.mangle.retailshopapp.recharge.exception.InvalidTenderException;
import com.mangle.retailshopapp.recharge.exception.LinesDoNotSumException;
import com.mangle.retailshopapp.recharge.exception.TooManyCreditLinesException;

/**
 * The whole arithmetic of settling one basket, and its validation
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
 * <p><b>What this type deliberately does NOT do.</b> It never invents a credit
 * line. §3.3: "a short or zero tender is expressed by the client as a smaller
 * CASH line plus a CREDIT line — the server does not silently invent credit. The
 * remainder-becomes-credit arithmetic is the desktop app's job to OFFER; the
 * server's job is to verify it sums." So a basket whose lines fall short is a
 * 422, not a tab quietly opened in someone's name.
 *
 * <p>No {@code Money} type (§5.2): {@code BigDecimal} at scale 2 matches
 * {@code decimal(38,2)} and every existing DTO.
 */
public record SettlementPlan(
        List<RechargeLine> recharges,
        List<SettlementLine> settlementLines,
        BigDecimal totalDue,
        BigDecimal cashTendered,
        BigDecimal changeDue) {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

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
     */
    public record RechargeLine(
            ServiceType serviceType,
            String number,
            String operatorCode,
            String routeCode,
            BigDecimal amount,
            BigDecimal surcharge) {

        public RechargeLine {
            amount = requireScale2(amount, "amount");
            surcharge = requireScale2(surcharge, "surcharge");
        }

        /** What the customer pays for this line. */
        public BigDecimal lineTotal() {
            return amount.add(surcharge);
        }
    }

    /**
     * One tender on the basket.
     *
     * <p>{@code amountTendered} is meaningful only on a {@code CASH} line and
     * {@code changeGiven} is derived from it, never sent by the client (§3.3).
     */
    public record SettlementLine(
            SettlementMethod method,
            BigDecimal amount,
            BigDecimal amountTendered,
            String reference,
            Integer customerId,
            String note) {

        public SettlementLine {
            amount = requireScale2(amount, "amount");
            amountTendered = amountTendered == null
                    ? null : requireScale2(amountTendered, "amountTendered");
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
        if (settlementLines == null || settlementLines.isEmpty()) {
            throw new InvalidTenderException("A basket needs at least one payment line.");
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
        // surcharge, so there is exactly one authority for it.
        BigDecimal totalDue = recharges.stream()
                .map(RechargeLine::lineTotal)
                .reduce(ZERO, BigDecimal::add);

        BigDecimal lineSum = ZERO;
        int creditLines = 0;
        BigDecimal cashLineAmount = null;
        BigDecimal cashTendered = null;

        for (SettlementLine line : settlementLines) {
            if (line.amount().signum() <= 0) {
                throw new InvalidTenderException(
                        "A payment line must be more than zero; got "
                                + line.amount().toPlainString() + ".");
            }
            lineSum = lineSum.add(line.amount());

            if (line.method() == SettlementMethod.CREDIT) {
                creditLines++;
                if (line.customerId() == null) {
                    throw new CreditRequiresCustomerException();
                }
            }

            if (line.method().carriesTender()) {
                if (cashLineAmount != null) {
                    // Two cash lines would make "the change" ambiguous and serve
                    // no purpose: one drawer, one tender.
                    throw new InvalidTenderException("A payment takes at most one cash line.");
                }
                cashLineAmount = line.amount();
                cashTendered = line.amountTendered() == null ? line.amount() : line.amountTendered();
                if (cashTendered.compareTo(cashLineAmount) < 0) {
                    throw new InvalidTenderException(cashTendered, cashLineAmount);
                }
            } else if (line.amountTendered() != null) {
                throw new InvalidTenderException(
                        "Only a cash line carries an amount tendered; " + line.method()
                                + " does not.");
            }
        }

        if (creditLines > 1) {
            throw new TooManyCreditLinesException(creditLines);
        }
        if (lineSum.compareTo(totalDue) != 0) {
            throw new LinesDoNotSumException(totalDue, lineSum);
        }

        BigDecimal tendered = cashTendered == null ? ZERO : cashTendered;
        BigDecimal change = cashLineAmount == null ? ZERO : tendered.subtract(cashLineAmount);

        return new SettlementPlan(List.copyOf(recharges), List.copyOf(settlementLines),
                totalDue, tendered, change);
    }

    /** The credit line, if the basket has one. */
    public SettlementLine creditLine() {
        return settlementLines.stream()
                .filter(l -> l.method() == SettlementMethod.CREDIT)
                .findFirst()
                .orElse(null);
    }

    public boolean hasCredit() {
        return creditLine() != null;
    }

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
                .reduce(ZERO, BigDecimal::add);
    }

    /** The recharge face value, surcharge excluded. */
    public BigDecimal totalRechargeAmount() {
        return recharges.stream()
                .map(RechargeLine::amount)
                .reduce(ZERO, BigDecimal::add);
    }

    /**
     * Whether the change should be shown at the counter (log §14.5).
     *
     * <p>⚠ BELOW THE THRESHOLD IT IS NOT DISPLAYED, AND THE RUPEE IS NOT HANDED
     * OVER. Confirmed with the owner: there is no coin and the customer waves it
     * away. ₹1 alone occurs 12,026 times in the FY data, and 34.4% of baskets
     * show change of ₹1-2 against 43% showing real change from a note — two
     * different events sharing one field.
     *
     * <p>Accepted consequence, recorded rather than solved: the drawer runs a
     * rupee over on those sales, roughly ₹1,000 a year, arriving at the evening
     * close as cash over. Capturing the kept rupee at the sale was proposed and
     * DECLINED by the owner — not worth a negotiation over a rupee in front of a
     * customer. §14.4's explain box is what names it at the close.
     *
     * <p>The threshold is a {@code rc_setting} row, not a UI constant, so it can
     * change without a release.
     */
    public boolean changeIsDisplayed(BigDecimal displayThreshold) {
        return changeDue.signum() > 0 && changeDue.compareTo(displayThreshold) >= 0;
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
