package com.mangle.retailshopapp.ticket.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mangle.retailshopapp.accounting.PostedVoucher;
import com.mangle.retailshopapp.accounting.model.AccVoucher;
import com.mangle.retailshopapp.accounting.model.AccVoucherLine;
import com.mangle.retailshopapp.accounting.repo.AccAccountRepository;
import com.mangle.retailshopapp.accounting.repo.AccVoucherLineRepository;
import com.mangle.retailshopapp.accounting.repo.AccVoucherRepository;
import com.mangle.retailshopapp.settlement.SettlementLine;
import com.mangle.retailshopapp.settlement.TenderPlan;
import com.mangle.retailshopapp.ticket.domain.BookingMode;
import com.mangle.retailshopapp.ticket.exception.BookingNotFoundException;
import com.mangle.retailshopapp.ticket.exception.InvalidBookingException;
import com.mangle.retailshopapp.ticket.exception.TicketFundingMismatchException;
import com.mangle.retailshopapp.ticket.exception.UnknownFundingAccountException;
import com.mangle.retailshopapp.ticket.model.TktBooking;
import com.mangle.retailshopapp.ticket.repo.TktBookingRepository;

/**
 * Booking a ticket — the one atomic write (design-ticket-booking.md §4).
 *
 * <p><b>The insert and the posting are one transaction.</b> A booking row with
 * no voucher is money the ledger does not know about; a voucher with no booking
 * row is money from nowhere. Neither is recoverable by a retry, so neither is
 * allowed to exist.
 *
 * <p>⚠ THE ORDER IS saveAndFlush THEN POST, and it is forced rather than chosen.
 * The idempotency key the ledger wants is
 * {@code TICKET_BOOKING:tkt_booking#<id>:CREATED}, and that id does not exist
 * until the row is inserted. So the row goes in, is flushed to get its id, and
 * only then is the voucher posted — the same shape
 * {@code RechargeFailureResolutionService} uses for a retry.
 */
@Service
public class TicketBookingService {

    /**
     * The four accounts the owner funds tickets from (log §10.6).
     *
     * <p>⚠ A LIST HERE AND AN ACTIVE-CHECK IN THE DATABASE, not one or the
     * other. The list says which accounts this business may name at all — a
     * booking funded from {@code CASH_WATER} is a mistake however active that
     * account is. The {@code IS_ACTIVE} lookup says whether the named one is
     * still in use, which a hardcoded list alone could never notice.
     */
    static final List<String> FUNDING_ACCOUNTS =
            List.of("BANK_JANATA", "BANK_SELF", "BANK_WIFE", "BANK_FATHER");

    private final TktBookingRepository bookings;
    private final TicketLedgerPostings postings;
    private final AccAccountRepository accounts;
    private final AccVoucherRepository vouchers;
    private final AccVoucherLineRepository voucherLines;
    private final Clock clock;

    public TicketBookingService(TktBookingRepository bookings,
                                TicketLedgerPostings postings,
                                AccAccountRepository accounts,
                                AccVoucherRepository vouchers,
                                AccVoucherLineRepository voucherLines,
                                Clock clock) {
        this.bookings = bookings;
        this.postings = postings;
        this.accounts = accounts;
        this.vouchers = vouchers;
        this.voucherLines = voucherLines;
        this.clock = clock;
    }

    /**
     * Creates one booking and posts its voucher.
     *
     * @param idempotencyKey the client's header key, stored on the row so a
     *                       retry after a lost response replays instead of
     *                       booking twice
     * @throws TicketFundingMismatchException a fare without its funding account, or vice versa
     * @throws UnknownFundingAccountException not one of the four, or not active
     * @throws InvalidBookingException        a passenger count below one, or negative commission
     */
    @Transactional
    public Booked book(BookingMode mode,
                       Integer passengerCount,
                       BigDecimal fareAmount,
                       BigDecimal commissionAmount,
                       String fundingAccountCode,
                       List<SettlementLine> settlementLines,
                       String note,
                       Integer staffUserId,
                       String idempotencyKey) {

        // --- Step 1: the rules, before any I/O that costs anything. ---------
        // Ordered cheapest first and so each message names one thing, matching
        // design-api.md §4.3 step 1.
        if (passengerCount == null || passengerCount < 1) {
            throw InvalidBookingException.passengerCount(passengerCount);
        }
        if (commissionAmount.signum() < 0) {
            throw InvalidBookingException.commission(commissionAmount);
        }

        // ck_tkt_funding, restated at the edge so the caller gets a sentence.
        boolean hasFare = fareAmount != null;
        boolean hasFunding = fundingAccountCode != null && !fundingAccountCode.isBlank();
        if (hasFare != hasFunding) {
            throw new TicketFundingMismatchException(hasFare);
        }
        if (hasFare && fareAmount.signum() <= 0) {
            // ck_tkt_fare says the same thing. A zero fare is not flow 2: "the
            // shop paid nothing" and "the shop was not involved" are different
            // events and only the second has no funding account.
            throw InvalidBookingException.commission(fareAmount);
        }

        if (hasFunding) {
            requireFundableAccount(fundingAccountCode);
        }

        // --- Step 2: the tender has to add up to what is being charged. -----
        // The total is computed HERE and nowhere else, then handed to the tender
        // as the figure it must match — so there is exactly one authority for it
        // and the client has nothing to disagree with.
        BigDecimal totalDue = hasFare ? fareAmount.add(commissionAmount) : commissionAmount;
        TenderPlan tender = TenderPlan.of(settlementLines, totalDue);

        LocalDateTime now = LocalDateTime.now(clock);

        // --- Step 3: the row. -----------------------------------------------
        TktBooking booking = new TktBooking();
        booking.setBookingDttm(now);
        booking.setBookingMode(mode);
        booking.setPassengerCount(passengerCount.shortValue());
        booking.setFareAmount(hasFare ? fareAmount : null);
        booking.setCommissionAmount(commissionAmount);
        booking.setFundingAccountCode(hasFare ? fundingAccountCode : null);
        // The customer is the credit line's, when there is one. A cash booking
        // stays anonymous (FRD §8.7:316).
        booking.setCustId(tender.hasCredit() ? tender.creditLine().customerId() : null);
        booking.setNote(note);
        booking.setIdempotencyKey(idempotencyKey);
        booking.setCreBy(staffUserId);

        // ⚠ FLUSHED, not merely saved. The voucher's idempotency key is the
        // booking's id, which does not exist until this statement reaches the
        // database — and it is also where a duplicate Idempotency-Key is refused,
        // by uk_tkt_idem, before any voucher is written.
        TktBooking saved = bookings.saveAndFlush(booking);

        // --- Step 4: the voucher, in the same transaction. ------------------
        PostedVoucher posted = postings.post(saved, tender, now);

        return new Booked(saved, tender, posted.voucherId());
    }

    /**
     * Whether this account may fund a ticket, and whether it still may.
     *
     * @throws UnknownFundingAccountException not one of the four, or inactive
     */
    private void requireFundableAccount(String code) {
        if (!FUNDING_ACCOUNTS.contains(code)) {
            throw new UnknownFundingAccountException(code, FUNDING_ACCOUNTS);
        }
        boolean active = accounts.findById(code)
                .map(com.mangle.retailshopapp.accounting.model.AccAccount::isActive)
                .orElse(false);
        if (!active) {
            // Deactivating an account should stop new bookings naming it, which
            // is exactly what a hardcoded list alone could never notice.
            throw new UnknownFundingAccountException(code, FUNDING_ACCOUNTS);
        }
    }

    /**
     * The replay path.
     *
     * <p>⚠ A FRESH READ-ONLY TRANSACTION, and that is the whole point.
     * {@code REQUIRES_NEW} because the caller reaches here from a catch block
     * after its own transaction has rolled back — including the row that caused
     * the duplicate — so the original booking is not visible from inside the
     * aborted one (design-api.md §7.1, RechargeController.java:240-247).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<TktBooking> replayed(String idempotencyKey) {
        return bookings.findByIdempotencyKey(idempotencyKey);
    }

    /**
     * A booking's posted voucher and its legs.
     *
     * <p>⚠ THIS IS WHAT STOPS A REPLAY LOOKING LIKE A FAILURE. The replayed
     * create returns through the read path, and a read that could not name the
     * voucher or the tender would answer a retry with "booking 1, no payment, no
     * voucher" — which is exactly the partial response design-api.md §7.1 warns
     * about for recharge: it "would make a replayed settle look like a
     * successful one with no recharges on it".
     *
     * <p>Found by the voucher's own idempotency key, which is derived from the
     * booking id, so this needs no second mapping table.
     */
    @Transactional(readOnly = true)
    public Optional<PostedLegs> voucherFor(long bookingId) {
        return vouchers.findByIdempotencyKey(TicketLedgerPostings.key(bookingId))
                .map(v -> new PostedLegs(v,
                        voucherLines.findByVoucherIdOrderByLineNo(v.getVoucherId())));
    }

    @Transactional(readOnly = true)
    public TktBooking byId(long bookingId) {
        return bookings.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
    }

    /** An inclusive date range, newest first. */
    @Transactional(readOnly = true)
    public List<TktBooking> onRange(LocalDate fromInclusive, LocalDate toInclusive) {
        return bookings.findByBookingDttmBetweenOrderByBookingDttmDesc(
                fromInclusive.atStartOfDay(), toInclusive.plusDays(1).atStartOfDay());
    }

    @Transactional(readOnly = true)
    public List<TktBooking> onDate(LocalDate date) {
        return bookings.findByBookingDttmBetweenOrderByBookingDttmDesc(
                date.atStartOfDay(), date.plusDays(1).atStartOfDay());
    }

    /** What was written, so the controller can answer without reading it back. */
    public record Booked(TktBooking booking, TenderPlan tender, long voucherId) {
    }

    /** A posted voucher and its legs, for answering a read. */
    public record PostedLegs(AccVoucher voucher, List<AccVoucherLine> legs) {
    }
}
