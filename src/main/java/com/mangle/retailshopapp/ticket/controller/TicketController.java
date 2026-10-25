package com.mangle.retailshopapp.ticket.controller;

import java.math.BigDecimal;
import java.net.URI;
import java.security.Principal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mangle.retailshopapp.settlement.Amounts;
import com.mangle.retailshopapp.settlement.SettlementLine;
import com.mangle.retailshopapp.ticket.domain.BookingMode;
import com.mangle.retailshopapp.ticket.model.TktBooking;
import com.mangle.retailshopapp.ticket.model.request.CreateBookingRequest;
import com.mangle.retailshopapp.ticket.model.response.BookingResponse;
import com.mangle.retailshopapp.ticket.model.response.TicketDaySummaryResponse;
import com.mangle.retailshopapp.ticket.service.TicketBookingService;
import com.mangle.retailshopapp.user.model.User;
import com.mangle.retailshopapp.user.repo.UserRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The four ticket endpoints (design-ticket-booking.md §4).
 *
 * <p>Naming follows design-api.md §1.1, so these are {@code /tickets/*} to match
 * {@code /recharge/*}.
 *
 * <p><b>⚠ No {@code PUT} and no {@code DELETE}</b>, and that is a decision
 * rather than an omission. Corrections go through FRD §8.3's regime, already
 * implemented by the ledger's trigger 4: before the account's reconciliation
 * point the voucher is deleted and re-posted, and after it a {@code JOURNAL}. A
 * ticket-specific edit endpoint would need its own copy of that rule, and two
 * copies of a correction rule is how they drift.
 *
 * <p><b>⚠ Errors reach this controller only because it is named in
 * {@code RechargeExceptionHandler}'s {@code assignableTypes}.</b> That advice is
 * deliberately not global — a global one would change the error shape of every
 * existing endpoint — so a controller it does not name gets Spring's default
 * error body instead. Every 422 here would otherwise arrive as a bare 500 with
 * no {@code code} on it, which is a thing that passes a service unit test and
 * fails the first time anyone uses the screen.
 */
@RestController
@RequestMapping("/tickets")
@Validated
public class TicketController {

    private final TicketBookingService bookingService;
    private final UserRepository users;
    private final Clock clock;

    public TicketController(TicketBookingService bookingService,
                            UserRepository users,
                            Clock clock) {
        this.bookingService = bookingService;
        this.users = users;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // #1 — the one atomic write
    // ------------------------------------------------------------------

    /**
     * Creates a booking and posts its voucher.
     *
     * <p>⚠ THE DUPLICATE-KEY RECOVERY IS HERE, AND IT HAS TO BE, for the reason
     * {@code RechargeController.java:240-247} gives: the duplicate throws and the
     * whole transaction rolls back INCLUDING the booking row, so the stored
     * result is not visible from inside the aborted transaction. This catch runs
     * after that rollback and looks the key up in a FRESH read-only transaction,
     * returning 200 with the original booking.
     *
     * <p>A pre-check {@code SELECT} before inserting would be a race in itself —
     * two retries can both read "not present" before either writes.
     * Insert-then-recover is correct and needs no locking.
     *
     * <p>What it protects: the server commits and posts, the response is lost on
     * the shop LAN, and the staff member presses again. Without this that is two
     * bookings and two {@code SALES} vouchers — the ledger says ₹970 was taken
     * while the drawer holds ₹485, silently until the evening count, and with no
     * {@code DELETE} to undo it.
     */
    @PostMapping("/bookings")
    public ResponseEntity<BookingResponse> create(
            @RequestHeader("Idempotency-Key")
            @NotBlank(message = "an Idempotency-Key header is required")
            @Size(max = 80, message = "an Idempotency-Key is at most 80 characters")
            String idempotencyKey,
            @Valid @RequestBody CreateBookingRequest request,
            Principal principal) {

        List<SettlementLine> lines = request.settlementLines().stream()
                .map(l -> new SettlementLine(l.method(), l.amount(), l.amountTendered(),
                        l.reference(), l.customerId(), l.note()))
                .toList();

        try {
            var booked = bookingService.book(
                    request.bookingMode(),
                    request.passengerCount(),
                    request.fareAmount(),
                    request.commissionAmount(),
                    request.fundingAccountCode(),
                    lines,
                    request.note(),
                    staffIdOf(principal),
                    idempotencyKey);

            return ResponseEntity
                    .created(URI.create("/tickets/bookings/" + booked.booking().getBookingId()))
                    .body(justBooked(booked.booking(), booked.tender(), booked.voucherId()));

        } catch (DataIntegrityViolationException e) {
            // The replay path. Only a key already on file turns into a 200;
            // anything else is a genuine integrity failure and is rethrown for the
            // handler, which must not report a constraint as a success.
            return bookingService.replayed(idempotencyKey)
                    .map(original -> ResponseEntity.ok(booking(original.getBookingId())))
                    .orElseThrow(() -> e);
        }
    }

    // ------------------------------------------------------------------
    // #2 — one booking
    // ------------------------------------------------------------------

    /**
     * One booking with its posted voucher id.
     *
     * <p>Not year-scoped, deliberately: a booking id is an exact reference, and
     * refusing to show last year's because a picker sits on this year is the
     * stored-mode failure design-api.md §9.3 forbids.
     *
     * <p>This is also the REPLAY path's return, which is why it reads the row
     * rather than echoing the request — a replayed create must answer with what
     * is actually on file.
     */
    @GetMapping("/bookings/{bookingId}")
    public BookingResponse booking(@PathVariable long bookingId) {
        TktBooking booking = bookingService.byId(bookingId);
        // ⚠ The tender is read from the VOUCHER, not reconstructed from the
        // booking. An earlier version of this method returned neither the lines
        // nor the voucher id, and the first end-to-end replay showed what that
        // costs: the retry came back as "booking 1, settlementLines: [],
        // voucherId: null" — a successful booking that reads as an unpaid one.
        return bookingService.voucherFor(bookingId)
                .map(posted -> toResponse(booking,
                        TicketResponseMapper.fromVoucher(posted.legs()),
                        posted.voucher().getVoucherId()))
                // No voucher means the row exists and nothing posted it, which
                // should be impossible inside one transaction. Reported as it is
                // rather than hidden behind an empty list.
                .orElseGet(() -> toResponse(booking, List.of(), null));
    }

    // ------------------------------------------------------------------
    // #3 — the list
    // ------------------------------------------------------------------

    /** A date range, newest first. {@code to} is inclusive of its whole day. */
    @GetMapping("/bookings")
    public Page<BookingResponse> bookings(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) BookingMode mode,
            @RequestParam(required = false) String fundingAccount,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        // Filtering in memory over a day range, not in the query, because the
        // volumes are tiny — a day holds single-figure bookings — and a derived
        // query per filter combination is four methods to keep in step. If this
        // ever needs a Specification, the repository is where it goes.
        List<TktBooking> rows = bookingService.onRange(from, to).stream()
                .filter(b -> mode == null || b.getBookingMode() == mode)
                .filter(b -> fundingAccount == null
                        || fundingAccount.equals(b.getFundingAccountCode()))
                .toList();

        int fromIndex = Math.min(page * size, rows.size());
        int toIndex = Math.min(fromIndex + size, rows.size());
        List<BookingResponse> content = rows.subList(fromIndex, toIndex).stream()
                .map(b -> toResponse(b, List.of(), null))
                .toList();

        return new org.springframework.data.domain.PageImpl<>(
                content, PageRequest.of(page, size), rows.size());
    }

    // ------------------------------------------------------------------
    // #4 — the day summary
    // ------------------------------------------------------------------

    /**
     * What ticketing did in a day.
     *
     * <p>⚠ The figures are readable on their own, which matters because
     * {@code CASH_TICKET} shares POOL-1 with {@code CASH_RECHARGE}: once bookings
     * post, a cash difference has two businesses in one drawer to be attributed
     * between, and a pool total alone would not say which.
     */
    @GetMapping("/day-summary")
    public TicketDaySummaryResponse daySummary(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        LocalDate on = date == null ? LocalDate.now(clock) : date;
        List<TktBooking> rows = bookingService.onDate(on);

        BigDecimal fareTotal = Amounts.ZERO;
        BigDecimal commissionTotal = Amounts.ZERO;
        int passengers = 0;
        Map<String, List<TktBooking>> byAccount = new LinkedHashMap<>();

        for (TktBooking b : rows) {
            passengers += b.getPassengerCount();
            commissionTotal = commissionTotal.add(b.getCommissionAmount());
            if (b.isShopFunded()) {
                fareTotal = fareTotal.add(b.getFareAmount());
                byAccount.computeIfAbsent(b.getFundingAccountCode(), k -> new ArrayList<>()).add(b);
            }
        }

        List<TicketDaySummaryResponse.FundingAccountTotal> perAccount = byAccount.entrySet().stream()
                .map(e -> new TicketDaySummaryResponse.FundingAccountTotal(
                        e.getKey(),
                        e.getValue().size(),
                        e.getValue().stream().map(TktBooking::getFareAmount)
                                .reduce(Amounts.ZERO, BigDecimal::add)))
                .sorted(Comparator.comparing(
                        TicketDaySummaryResponse.FundingAccountTotal::accountCode))
                .toList();

        return new TicketDaySummaryResponse(
                on,
                rows.size(),
                passengers,
                fareTotal,
                commissionTotal,
                fareTotal.add(commissionTotal),
                perAccount);
    }

    // ------------------------------------------------------------------
    // Mapping
    // ------------------------------------------------------------------

    /** The 201's body, where the tender is still in hand. */
    private static BookingResponse justBooked(TktBooking b,
                                              com.mangle.retailshopapp.settlement.TenderPlan tender,
                                              Long voucherId) {
        return toResponse(b, TicketResponseMapper.lines(tender), voucherId);
    }

    private static BookingResponse toResponse(TktBooking b,
                                              List<com.mangle.retailshopapp.ticket.model.response
                                                      .SettlementLineResponse> lines,
                                              Long voucherId) {
        return new BookingResponse(
                b.getBookingId(),
                b.getBookingDttm(),
                b.getBookingMode(),
                b.getPassengerCount(),
                b.getFareAmount(),
                b.getCommissionAmount(),
                b.getFundingAccountCode(),
                b.isShopFunded(),
                b.totalDue(),
                b.getCustId(),
                b.getNote(),
                lines,
                voucherId);
    }

    private Integer staffIdOf(Principal principal) {
        if (principal == null) {
            return null;
        }
        // findByUsername returns the entity or null rather than an Optional, which
        // is the existing repository's signature and not this slice's to change.
        User user = users.findByUsername(principal.getName());
        return user == null ? null : user.getId();
    }
}
