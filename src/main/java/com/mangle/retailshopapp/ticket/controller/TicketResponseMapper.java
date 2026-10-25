package com.mangle.retailshopapp.ticket.controller;

import java.math.BigDecimal;
import java.util.List;

import com.mangle.retailshopapp.accounting.model.AccVoucherLine;
import com.mangle.retailshopapp.settlement.SettlementLine;
import com.mangle.retailshopapp.settlement.SettlementMethod;
import com.mangle.retailshopapp.settlement.TenderPlan;
import com.mangle.retailshopapp.ticket.model.response.SettlementLineResponse;

/**
 * The tender, as the wire sees it.
 *
 * <p>⚠ {@code changeGiven} and {@code changeDisplayed} come from the
 * {@code TenderPlan} and are never re-derived by a client: log §14.5 keeps change
 * under the threshold off the screen, and the rule lives server-side so two
 * clients cannot disagree about it.
 *
 * <p>A separate class rather than more methods on the controller, so the
 * threshold is passed in one place when the screen starts reading it from config
 * rather than from this constant.
 */
final class TicketResponseMapper {

    /**
     * §14.5's threshold.
     *
     * <p>⚠ A CONSTANT HERE AND A SETTING FOR RECHARGE, which is a difference
     * worth naming rather than hiding: the recharge counter reads
     * {@code changeDisplayThreshold} from {@code rc_setting} through its config
     * endpoint, and the ticket screen has no config endpoint yet. The figure is
     * the same ₹5 and the rule is the same rule. Wiring this to the same setting
     * is a small, obvious follow-up; inventing a second ticket-specific setting
     * would not be.
     */
    private static final BigDecimal CHANGE_DISPLAY_THRESHOLD = new BigDecimal("5.00");

    private TicketResponseMapper() {
    }

    /**
     * The tender as the VOUCHER recorded it, for a read after the fact.
     *
     * <p>⚠ Only the debit legs are tenders. A credit leg is where the money went
     * — the funding account or income — and is not something the customer paid
     * with.
     *
     * <p>⚠ WHAT A RE-READ CANNOT SAY, stated rather than faked: the voucher
     * records the amount RECEIVED into each account, not what was physically
     * handed over, and {@code tkt_booking} does not store a tender or a change
     * figure either. So {@code amountTendered}, {@code changeGiven} and
     * {@code changeDisplayed} come back NULL from this path. They are a fact
     * about the moment of sale and are on the 201; inventing them later — say by
     * assuming exact money — would be a figure the shop never recorded. If the
     * owner ever wants change visible on a re-read, that is two columns on
     * tkt_booking, not a derivation here.
     */
    static List<SettlementLineResponse> fromVoucher(List<AccVoucherLine> legs) {
        return legs.stream()
                .filter(leg -> leg.getDrAmount() != null && leg.getDrAmount().signum() > 0)
                .filter(leg -> leg.getPaymentMethod() != null)
                .map(leg -> new SettlementLineResponse(
                        // lineType() -> the enum: the column holds CRDT and the
                        // wire says CREDIT, so this has to map back the same way
                        // it mapped out.
                        SettlementMethod.fromLineType(leg.getPaymentMethod()),
                        leg.getDrAmount(),
                        null,
                        null,
                        null,
                        leg.getAccountCode().equals("UPI_CLEARING") ? leg.getLineNarration() : null,
                        leg.getPartyCustId(),
                        leg.getAccountCode().equals("AR_TICKET") ? leg.getLineNarration() : null))
                .toList();
    }

    static List<SettlementLineResponse> lines(TenderPlan tender) {
        boolean displayed = tender.changeIsDisplayed(CHANGE_DISPLAY_THRESHOLD);
        return tender.lines().stream()
                .map(line -> toLine(line, tender, displayed))
                .toList();
    }

    private static SettlementLineResponse toLine(SettlementLine line, TenderPlan tender,
                                                 boolean displayed) {
        boolean cash = line.method().carriesTender();
        return new SettlementLineResponse(
                line.method(),
                line.amount(),
                cash ? tender.cashTendered() : null,
                // The change belongs to the cash line and to no other, because the
                // cash line is the only one with a tender behind it.
                cash ? tender.changeDue() : null,
                cash && displayed,
                line.reference(),
                line.customerId(),
                line.note());
    }
}
