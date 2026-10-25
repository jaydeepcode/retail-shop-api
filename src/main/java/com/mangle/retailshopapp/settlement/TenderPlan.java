package com.mangle.retailshopapp.settlement;

import java.math.BigDecimal;
import java.util.List;

import com.mangle.retailshopapp.recharge.exception.CreditRequiresCustomerException;
import com.mangle.retailshopapp.recharge.exception.InvalidTenderException;
import com.mangle.retailshopapp.recharge.exception.LinesDoNotSumException;
import com.mangle.retailshopapp.recharge.exception.TooManyCreditLinesException;

/**
 * How a sale was paid for: the lines, and everything derived from them.
 *
 * <p>Given a total to match, this is the whole of what the server checks about
 * a tender — that the lines add up to it, that there is at most one credit line
 * and it names somebody, that only cash carries a tender, and that the tender
 * covers its own cash line. The change falls out of the last of those.
 *
 * <p><b>What it deliberately does NOT do.</b> It never invents a credit line.
 * §3.3: "a short or zero tender is expressed by the client as a smaller CASH
 * line plus a CREDIT line — the server does not silently invent credit. The
 * remainder-becomes-credit arithmetic is the desktop app's job to OFFER; the
 * server's job is to verify it sums." So a sale whose lines fall short is a
 * 422, not a tab quietly opened in someone's name.
 *
 * <p><b>What it does not know.</b> What was sold, or how the total was arrived
 * at. A basket of recharges reaches that total one way (each line's amount plus
 * its own surcharge) and a ticket another ({@code fareAmount +
 * commissionAmount}); neither is this type's business. {@code totalDue} is
 * given to it, and is the caller's single authority for that figure.
 *
 * <p>Pure arithmetic, no database, no mocks, no Spring context (§5.4 rule 4).
 */
public record TenderPlan(
        List<SettlementLine> lines,
        BigDecimal totalDue,
        BigDecimal cashTendered,
        BigDecimal changeDue) {

    /**
     * Validates the tender against a total and computes everything derived.
     *
     * <p>Ordered so the cheapest rejections happen first and so each message
     * names one thing — §4.3 step 1 is explicit that this runs before ANY I/O,
     * so a malformed sale costs no database round trip at all.
     *
     * @param lines    the tender, at least one
     * @param totalDue what they must add up to, already at scale 2
     * @throws LinesDoNotSumException          the lines do not match the total
     * @throws CreditRequiresCustomerException a credit line with no customer
     * @throws TooManyCreditLinesException     more than one credit line
     * @throws InvalidTenderException          tender on a non-cash line, or short of its own line
     */
    public static TenderPlan of(List<SettlementLine> lines, BigDecimal totalDue) {
        if (lines == null || lines.isEmpty()) {
            throw new InvalidTenderException("A basket needs at least one payment line.");
        }

        BigDecimal lineSum = Amounts.ZERO;
        int creditLines = 0;
        BigDecimal cashLineAmount = null;
        BigDecimal cashTendered = null;

        for (SettlementLine line : lines) {
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

        BigDecimal tendered = cashTendered == null ? Amounts.ZERO : cashTendered;
        BigDecimal change = cashLineAmount == null ? Amounts.ZERO : tendered.subtract(cashLineAmount);

        return new TenderPlan(List.copyOf(lines), totalDue, tendered, change);
    }

    /** The credit line, if the sale has one. */
    public SettlementLine creditLine() {
        return lines.stream()
                .filter(l -> l.method() == SettlementMethod.CREDIT)
                .findFirst()
                .orElse(null);
    }

    public boolean hasCredit() {
        return creditLine() != null;
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
}
