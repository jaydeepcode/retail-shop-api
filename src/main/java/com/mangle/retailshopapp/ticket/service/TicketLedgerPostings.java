package com.mangle.retailshopapp.ticket.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mangle.retailshopapp.accounting.LedgerPostingService;
import com.mangle.retailshopapp.accounting.PostedVoucher;
import com.mangle.retailshopapp.accounting.VoucherDraft;
import com.mangle.retailshopapp.accounting.VoucherLeg;
import com.mangle.retailshopapp.settlement.SettlementLine;
import com.mangle.retailshopapp.settlement.TenderPlan;
import com.mangle.retailshopapp.ticket.model.TktBooking;

/**
 * The ticket booking posting — E23, E24 and E25 of design-ledger.md §11.6.
 *
 * <p><b>One voucher per booking</b>, unlike recharge's two. Recharge splits
 * because the money arrives before the work is done and the failure path is the
 * common one; a booking has no such gap. The ticket is issued at the counter, so
 * there is nothing to come back and confirm and no pending account to park
 * anything in.
 *
 * <p><b>The two flows, which are one rule.</b>
 *
 * <pre>
 * flow 1   Dr CASH_TICKET 485.00  /  Cr BANK_SELF 425.00  +  Cr INC_TKT_COMM 60.00
 * flow 2   Dr CASH_TICKET  60.00  /  Cr INC_TKT_COMM 60.00
 * </pre>
 *
 * <p>The difference is only whether a fare leg exists, and that is read from the
 * row ({@link TktBooking#isShopFunded()}) rather than passed in as a flag.
 * {@code ck_tkt_funding} guarantees a fare and its funding account are present
 * together or absent together, so there is no third case to handle and no way
 * for the caller to ask for an inconsistent one.
 *
 * <p>E25 — a fare with no commission, 17 historical baskets — needs no branch:
 * it is flow 1 with a zero commission leg, and a zero leg is simply omitted
 * rather than posted, because {@code ck_line_nonzero} refuses a leg that moves
 * nothing.
 *
 * <p><b>A {@code @Component}, not a ninth service</b>, for the same reason
 * {@code RechargeLedgerPostings} is one: it is the ledger adapter, holding the
 * leg construction and the idempotency key in one place.
 *
 * <p>Every method is {@code propagation = MANDATORY}, matching
 * {@code LedgerPostingService} — design-ledger.md §5.1 calls that "the single
 * most valuable line in this design". A caller that forgets
 * {@code @Transactional} throws on its first posting rather than quietly opening
 * a transaction of its own and committing the voucher separately from the row it
 * describes.
 */
@Component
public class TicketLedgerPostings {

    private static final String TYPE_SALES = "SALES";
    private static final String SOURCE_TICKET_BOOKING = "TICKET_BOOKING";

    /** The receiving accounts, per FRD §8.6 and §8.7. */
    private static final String CASH_TICKET = "CASH_TICKET";
    private static final String UPI_CLEARING = "UPI_CLEARING";
    private static final String AR_TICKET = "AR_TICKET";

    private static final String INC_TKT_COMM = "INC_TKT_COMM";

    private final LedgerPostingService ledger;

    public TicketLedgerPostings(LedgerPostingService ledger) {
        this.ledger = ledger;
    }

    /**
     * Posts one booking.
     *
     * <p>⚠ THE KEY IS THE BOOKING'S ID, WHICH DOES NOT EXIST UNTIL THE ROW IS
     * INSERTED. So the caller's order is {@code saveAndFlush} then post, inside
     * one transaction — the same shape {@code RechargeFailureResolutionService}
     * uses for a retry. Posting first would have nothing to key on, and keying on
     * anything else would let the same booking post twice.
     *
     * @param booking the booking, already flushed so {@code bookingId} is set
     * @param tender  how the customer paid, summing to {@link TktBooking#totalDue()}
     * @param when    the voucher's date and time
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PostedVoucher post(TktBooking booking, TenderPlan tender, LocalDateTime when) {
        if (booking.getBookingId() == null) {
            // Not an assertion for its own sake: this is the one ordering mistake
            // the idempotency key cannot survive, and it would otherwise surface
            // as a key of "tkt_booking#null:CREATED" that collides with every
            // other unflushed booking.
            throw new IllegalStateException(
                    "The booking must be flushed before posting: the idempotency key is "
                            + "its id.");
        }

        List<VoucherLeg> legs = new ArrayList<>();

        // -- the debit: how the customer paid ---------------------------------
        // One leg per settlement line, because the three tenders land in three
        // different accounts and the day's reconciliation needs them apart.
        for (SettlementLine line : tender.lines()) {
            // ⚠ lineType(), NOT name(). acc_voucher_line.PAYMENT_METHOD
            // foreign-keys ref_payment_line_type, whose three codes are CASH, UPI
            // and CRDT — the wire name is CREDIT and the column value is CRDT.
            // Using the enum's name fails fk_line_paymeth on every credit sale,
            // which only appears when a booking actually carries credit
            // (RechargeLedgerPostings.java:150-157 records the same trap).
            legs.add(switch (line.method()) {
                case CASH -> VoucherLeg.debit(CASH_TICKET, line.amount())
                        .withPaymentMethod(line.method().lineType());
                case UPI -> VoucherLeg.debit(UPI_CLEARING, line.amount())
                        .withPaymentMethod(line.method().lineType())
                        .withNarration(line.reference());
                // AR_TICKET has REQUIRES_PARTY = TRUE, and LedgerPostingService
                // refuses a receivable leg with no party on it: a receivable that
                // reaches nobody's statement is not a receivable.
                case CREDIT -> VoucherLeg.debit(AR_TICKET, line.amount())
                        .withParty(line.customerId())
                        .withPaymentMethod(line.method().lineType())
                        .withNarration(line.note());
            });
        }

        // -- the credits: where the money goes --------------------------------
        if (booking.isShopFunded()) {
            // Flow 1. The fare is a pass-through: the shop paid the platform from
            // one of the four bank accounts and is reimbursed by the customer, so
            // it never touches P&L. Only the commission is income.
            legs.add(VoucherLeg.credit(booking.getFundingAccountCode(), booking.getFareAmount())
                    .withNarration("Fare paid to the platform"));
        }

        if (booking.getCommissionAmount().signum() > 0) {
            // E25's zero-commission booking posts no income leg at all, because
            // ck_line_nonzero refuses a leg that moves nothing. Seventeen
            // historical baskets look like this and they are flow 1 at zero
            // margin rather than an error.
            legs.add(VoucherLeg.credit(INC_TKT_COMM, booking.getCommissionAmount()));
        }

        VoucherDraft draft = new VoucherDraft(
                TYPE_SALES,
                when.toLocalDate(),
                when,
                narrationFor(booking),
                SOURCE_TICKET_BOOKING,
                "tkt_booking#" + booking.getBookingId(),
                key(booking.getBookingId()),
                null,
                booking.getCreBy(),
                legs);

        return ledger.post(draft);
    }

    /**
     * {@code TICKET_BOOKING:tkt_booking#<id>:CREATED}, per design-ledger.md §5.4.
     *
     * <p>The {@code :CREATED} stage suffix is what lets a correction coexist with
     * the original: an amendment before reconciliation is a delete-and-repost
     * under this same key, and after reconciliation it is a {@code JOURNAL} under
     * its own (FRD §8.3, trigger 4).
     */
    static String key(long bookingId) {
        return SOURCE_TICKET_BOOKING + ":tkt_booking#" + bookingId + ":CREATED";
    }

    private static String narrationFor(TktBooking booking) {
        String mode = booking.getBookingMode() == null ? "Ticket"
                : switch (booking.getBookingMode()) {
                    case RAIL -> "Railway";
                    case BUS -> "Bus";
                };
        BigDecimal passengers = BigDecimal.valueOf(booking.getPassengerCount());
        return "%s booking B-%d, %s passenger%s".formatted(
                mode, booking.getBookingId(), passengers.toPlainString(),
                booking.getPassengerCount() == 1 ? "" : "s");
    }
}
