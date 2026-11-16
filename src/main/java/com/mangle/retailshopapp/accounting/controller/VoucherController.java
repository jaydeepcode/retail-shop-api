package com.mangle.retailshopapp.accounting.controller;

import java.net.URI;
import java.security.Principal;
import java.util.function.Supplier;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mangle.retailshopapp.accounting.ManualVoucherService;
import com.mangle.retailshopapp.accounting.model.request.ContraRequest;
import com.mangle.retailshopapp.accounting.model.request.JournalRequest;
import com.mangle.retailshopapp.accounting.model.request.PaymentRequest;
import com.mangle.retailshopapp.accounting.model.request.PurchaseRequest;
import com.mangle.retailshopapp.accounting.model.response.VoucherResponse;
import com.mangle.retailshopapp.user.model.User;
import com.mangle.retailshopapp.user.repo.UserRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Endpoints 7, 8, 9 and 19 of design-ledger-api.md §1.3 — the vouchers a person
 * composes.
 *
 * <p>One endpoint per voucher type (A5). There is deliberately <b>no generic
 * {@code POST /vouchers}</b>, no {@code POST /vouchers/sales} and no
 * {@code POST /vouchers/receipts} (§6): a hand-written {@code SALES} voucher
 * would bypass the float legs, the commission computation and the idempotency
 * keys that make those vouchers correct, and a free receipt endpoint would let a
 * repayment be written without the ageing rule or a variance be explained without
 * the close that knows what it is explaining.
 *
 * <p>⚠ <b>EVERY WRITE HERE TAKES A CLIENT-GENERATED {@code Idempotency-Key}</b>,
 * which is the opposite of {@code AccountsController}'s two closes. The reason is
 * not consistency either way: <b>only the client can tell a retry from a second
 * real bill</b>, because two identical ₹1,200 electricity payments dated the same
 * day are indistinguishable in the payload, while "close POOL-1 for 9 Nov" is
 * true at most once and so has a key the server can derive (§1.3).
 *
 * <p>⚠ <b>ERRORS REACH THIS CONTROLLER ONLY BECAUSE IT IS NAMED IN
 * {@code RechargeExceptionHandler}'s {@code assignableTypes}.</b> There is no
 * global advice in this application and there must not be — it would change the
 * error shape of every existing endpoint, including the ones the water mobile app
 * calls. A controller the advice does not name gets Spring's default error body:
 * a bare 500 with no {@code code}, for what should be a 422. <b>Forgetting is
 * silent and passes every service test</b>, because the service throws correctly
 * either way and the failure is entirely in whether anything is listening.
 * {@code VoucherControllerErrorsTest} is the guard and it was verified by
 * reverting the annotation.
 *
 * <p>⚠ <b>AND {@code /vouchers/**} IS ROLE-GATED IN THIS SAME RELEASE</b>, which
 * §3 requires in as many words: these endpoints move money between accounts, and
 * {@code SecurityConfig}'s fall-through would let a water {@code ROLE_CUSTOMER}
 * token reach them, since no {@code ROLE_STAFF} exists.
 */
@RestController
@RequestMapping("/vouchers")
@Validated
public class VoucherController {

    private final ManualVoucherService vouchers;
    private final UserRepository users;

    public VoucherController(ManualVoucherService vouchers, UserRepository users) {
        this.vouchers = vouchers;
        this.users = users;
    }

    // ------------------------------------------------------------------
    // #7 — an expense
    // ------------------------------------------------------------------

    /** {@code Dr EXP_<category> / Cr <source>} (E19, FRD §6). */
    @PostMapping("/payments")
    public ResponseEntity<VoucherResponse> payment(
            @RequestHeader("Idempotency-Key") @NotBlank(
                    message = "an Idempotency-Key header is required: only the client can tell a "
                            + "retry from a second real bill")
            @Size(max = 80, message = "an Idempotency-Key is at most 80 characters")
            String idempotencyKey,
            @Valid @RequestBody PaymentRequest request,
            Principal principal) {

        return created(idempotencyKey,
                () -> vouchers.postPayment(request, idempotencyKey, staffIdOf(principal)));
    }

    // ------------------------------------------------------------------
    // #8 — a transfer between the shop's own accounts
    // ------------------------------------------------------------------

    /** {@code Dr <to> / Cr <from>}, never touching P&L (E20, FRD §8.2). */
    @PostMapping("/contras")
    public ResponseEntity<VoucherResponse> contra(
            @RequestHeader("Idempotency-Key") @NotBlank(
                    message = "an Idempotency-Key header is required")
            @Size(max = 80, message = "an Idempotency-Key is at most 80 characters")
            String idempotencyKey,
            @Valid @RequestBody ContraRequest request,
            Principal principal) {

        return created(idempotencyKey,
                () -> vouchers.postContra(request, idempotencyKey, staffIdOf(principal)));
    }

    // ------------------------------------------------------------------
    // #9 — a correction
    // ------------------------------------------------------------------

    /**
     * A correction after the reconciliation point (FRD §8.3, E21), dated the day
     * the mistake was found.
     *
     * <p>⚠ <b>ACCEPTED AT ANY TIME.</b> The 422 on "target account not yet
     * reconciled" was withdrawn on 2026-11-02 — the module that owns the record
     * chooses between amending and journalling, and a ledger-native voucher has
     * no record to amend, so the refusal left an expense with no correction route
     * at all until its drawer was counted.
     */
    @PostMapping("/journals")
    public ResponseEntity<VoucherResponse> journal(
            @RequestHeader("Idempotency-Key") @NotBlank(
                    message = "an Idempotency-Key header is required")
            @Size(max = 80, message = "an Idempotency-Key is at most 80 characters")
            String idempotencyKey,
            @Valid @RequestBody JournalRequest request,
            Principal principal) {

        return created(idempotencyKey,
                () -> vouchers.postJournal(request, idempotencyKey, staffIdOf(principal)));
    }

    // ------------------------------------------------------------------
    // #19 — a float top-up
    // ------------------------------------------------------------------

    /**
     * A float top-up: the float up, the transfer fee as its own expense, the
     * paying account down (E6/E7, log §2.5).
     */
    @PostMapping("/purchases")
    public ResponseEntity<VoucherResponse> purchase(
            @RequestHeader("Idempotency-Key") @NotBlank(
                    message = "an Idempotency-Key header is required")
            @Size(max = 80, message = "an Idempotency-Key is at most 80 characters")
            String idempotencyKey,
            @Valid @RequestBody PurchaseRequest request,
            Principal principal) {

        return created(idempotencyKey,
                () -> vouchers.postPurchase(request, idempotencyKey, staffIdOf(principal)));
    }

    // ------------------------------------------------------------------
    // The one shared response shape
    // ------------------------------------------------------------------

    /**
     * 201 on a first post, <b>409 carrying the original on a replay</b>, and the
     * duplicate-key recovery for the race between two concurrent first-posts.
     *
     * <p>⚠ <b>409 AND NOT 200.</b> §1.3's code list says 201, 409, 422 for all
     * four of these, and {@code PostedVoucher.replayed}'s own javadoc draws the
     * distinction: an AUTOMATIC posting treats a replay as success, while a
     * USER-INITIATED action reports it so the person learns it was already done.
     * The ticket module's booking replay answers 200 because its caller is
     * re-reading a booking it made; here somebody has pressed a button twice and
     * should be told. The body is the original voucher either way, which is what
     * makes the 409 actionable rather than a dead end — the same reasoning as the
     * close's 409 and {@code RechargeController.java:593}'s duplicate mobile.
     *
     * <p>⚠ <b>THE CATCH IS THE RACE, NOT THE RETRY.</b> An ordinary retry never
     * gets here: {@code ManualVoucherService} looks the key up before it writes
     * anything, which it must, because a top-up's batch and movement rows would
     * otherwise already be in flight. What reaches this catch is two concurrent
     * FIRST posts, where both passed that lookup and {@code uk_vch_idem} refused
     * the second — and at that point the transaction has rolled back, so the
     * winner's voucher is invisible from inside it and the re-read has to happen
     * in a FRESH read-only transaction.
     *
     * <p>Anything that is not a key already on file is rethrown, because an
     * integrity failure must never be reported as an already-done.
     */
    private ResponseEntity<VoucherResponse> created(String idempotencyKey,
                                                    Supplier<VoucherResponse> post) {
        try {
            VoucherResponse posted = post.get();
            return posted.replayed()
                    ? ResponseEntity.status(HttpStatus.CONFLICT).body(posted)
                    : ResponseEntity.created(URI.create("/vouchers/" + posted.voucherId()))
                            .body(posted);

        } catch (DataIntegrityViolationException e) {
            return vouchers.replayed(idempotencyKey)
                    .map(original -> ResponseEntity.status(HttpStatus.CONFLICT).body(original))
                    .orElseThrow(() -> e);
        }
    }

    private Integer staffIdOf(Principal principal) {
        if (principal == null) {
            return null;
        }
        // findByUsername returns the entity or null rather than an Optional,
        // which is the existing repository's signature and not this slice's to
        // change.
        User user = users.findByUsername(principal.getName());
        return user == null ? null : user.getId();
    }
}
