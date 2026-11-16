package com.mangle.retailshopapp.accounting.controller;

import java.net.URI;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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

import com.mangle.retailshopapp.accounting.AccountDirectoryService;
import com.mangle.retailshopapp.accounting.LedgerCloseService;
import com.mangle.retailshopapp.accounting.UpiSettlementService;
import com.mangle.retailshopapp.accounting.model.request.PoolCloseRequest;
import com.mangle.retailshopapp.accounting.model.request.ReconciliationRequest;
import com.mangle.retailshopapp.accounting.model.request.UpiSettlementRequest;
import com.mangle.retailshopapp.accounting.model.response.AccountResponse;
import com.mangle.retailshopapp.accounting.model.response.ClosePreviewResponse;
import com.mangle.retailshopapp.accounting.model.response.PoolCloseResponse;
import com.mangle.retailshopapp.accounting.model.response.ReconciliationResponse;
import com.mangle.retailshopapp.accounting.model.response.UpiPendingResponse;
import com.mangle.retailshopapp.accounting.model.response.UpiSettlementResponse;
import com.mangle.retailshopapp.user.model.User;
import com.mangle.retailshopapp.user.repo.UserRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Endpoints 1 to 4 of design-ledger-api.md §1.1 — the writes the FRD §8.3
 * correction regime depends on — plus the picker list (#12) and the UPI
 * settlement pair (#5, #6) from §1.2 and §1.3.
 *
 * <p>⚠ The three later endpoints live here rather than on a controller of their
 * own because they are {@code /accounts/*} paths and Spring maps a path prefix
 * once: a second {@code @RequestMapping("/accounts")} controller would work but
 * would leave a reader guessing which of the two serves a given path. The close
 * is still what this class is mostly about.
 *
 * <p>Naming follows design-api.md §1.1, so these are {@code /accounts/*} beside
 * {@code /recharge/*} and {@code /tickets/*}, and the package mirrors
 * {@code ticket/controller/}.
 *
 * <p>⚠ <b>NO {@code Idempotency-Key} HEADER ON EITHER WRITE</b>, which is a
 * deliberate exception to the convention every other write in this application
 * follows (§1.3, "Idempotency, endpoint by endpoint"). A close carries its own
 * identity — "close POOL-1 for 9 Nov" is true at most once — so the key is
 * server-derived and {@code uk_recon_account_date} is the guard. A required
 * header whose value the server ignores is worse than none, because it tells a
 * client the value matters and someone will send a fresh one per click.
 *
 * <p>⚠ <b>ERRORS REACH THIS CONTROLLER ONLY BECAUSE IT IS NAMED IN
 * {@code RechargeExceptionHandler}'s {@code assignableTypes}.</b> That advice is
 * scoped rather than global — a global one would change the error shape of every
 * existing endpoint, including the ones the water mobile app calls — so a
 * controller it does not name gets Spring's default error body: a bare 500 with
 * no {@code code}, for what should be a 422. <b>Forgetting is silent and passes
 * every service test</b>, because the service throws correctly either way and
 * the failure is entirely in whether anything is listening.
 * {@code AccountsControllerErrorsTest} is the guard, and it was verified by
 * reverting the annotation and watching it fail.
 */
@RestController
@RequestMapping("/accounts")
@Validated
public class AccountsController {

    private final LedgerCloseService closeService;
    private final AccountDirectoryService directory;
    private final UpiSettlementService upiSettlements;
    private final UserRepository users;

    public AccountsController(LedgerCloseService closeService,
                              AccountDirectoryService directory,
                              UpiSettlementService upiSettlements,
                              UserRepository users) {
        this.closeService = closeService;
        this.directory = directory;
        this.upiSettlements = upiSettlements;
        this.users = users;
    }

    // ------------------------------------------------------------------
    // #12 — the picker list
    // ------------------------------------------------------------------

    /**
     * Active accounts with type, business, pool and the {@code ALLOW_MANUAL} flag
     * (endpoint 12).
     *
     * <p>⚠ <b>{@code manualOnly=true} IS WHAT A VOUCHER SCREEN ASKS FOR</b>, and
     * it exists so a screen does not have to know A7's list. The machine-owned
     * accounts — {@code UPI_CLEARING}, {@code RCHG_PENDING}, the four floats and
     * the two cash-variance accounts — are moved only by the endpoints that own
     * them, so a picker that offered them would be collecting 422s. The flag is
     * also on every row, so a screen that wants to show them greyed out with a
     * reason can.
     *
     * <p>⚠ NO {@code Page}, unlike endpoint 3. The chart of accounts is ~30 rows
     * that never change identity (L1) and a picker needs all of them to render; a
     * paged picker would make the screen ask twice for a list that fits in one
     * response.
     *
     * <p>An unknown {@code type} or {@code business} returns an empty list rather
     * than a 400 — see {@link AccountDirectoryService#directory}.
     */
    @GetMapping
    public List<AccountResponse> accounts(
            @RequestParam(defaultValue = "false") boolean manualOnly,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String business) {

        return directory.directory(manualOnly, type, business);
    }

    // ------------------------------------------------------------------
    // #5 — what is still in transit
    // ------------------------------------------------------------------

    /**
     * UPI still in transit, grouped by collection date and aged (endpoint 5).
     *
     * <p>⚠ A LITERAL PATH SEGMENT BESIDE {@code /accounts/{accountCode}/…}, and
     * Spring prefers the literal — so {@code /accounts/upi-clearing/pending} does
     * not collide with the close history. Lower-case and hyphenated because it is
     * a RESOURCE here rather than an account code: the code is
     * {@code UPI_CLEARING}, and endpoint 4 still closes it under that name.
     *
     * @param asOf the ageing date; today when omitted
     */
    @GetMapping("/upi-clearing/pending")
    public UpiPendingResponse upiPending(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {

        return upiSettlements.pending(asOf);
    }

    // ------------------------------------------------------------------
    // #6 — recording what the bank credited
    // ------------------------------------------------------------------

    /**
     * Records what the bank actually credited (endpoint 6, A4).
     *
     * <p>⚠ <b>AN {@code Idempotency-Key} HEADER, CLIENT-GENERATED</b> — and that
     * is a correction to §1.3's table, taken with the owner on 2026-11-16. §1.3
     * had the key server-derived as
     * {@code UPI_SETTLEMENT:<collectionDate>:<n>} AND stored on
     * {@code uk_vch_idem}, which cannot detect a retry: the server would derive
     * {@code n + 1} and record the ₹3,200 twice. The sequence is still derived and
     * still published, on {@code SOURCE_REF}; the header is what makes a second
     * click harmless. See {@code UpiSettlementService}.
     *
     * <p>⚠ <b>MORE THAN ONE SETTLEMENT PER COLLECTION DATE IS NORMAL</b> (A4,
     * E16b), which is why this does not refuse a date that already has one. It
     * refuses only an amount larger than what is still pending.
     */
    @PostMapping("/upi-clearing/settlements")
    public ResponseEntity<UpiSettlementResponse> settleUpi(
            @RequestHeader("Idempotency-Key") @NotBlank(
                    message = "an Idempotency-Key header is required: a settlement may be "
                            + "recorded more than once for one collection date, so only the "
                            + "client can tell a retry from a second real credit")
            @Size(max = 80, message = "an Idempotency-Key is at most 80 characters")
            String idempotencyKey,
            @Valid @RequestBody UpiSettlementRequest request,
            Principal principal) {

        try {
            UpiSettlementResponse settled =
                    upiSettlements.settle(request, idempotencyKey, staffIdOf(principal));
            return settled.replayed()
                    ? ResponseEntity.status(HttpStatus.CONFLICT).body(settled)
                    : ResponseEntity
                            .created(URI.create("/vouchers/" + settled.voucherId()))
                            .body(settled);

        } catch (DataIntegrityViolationException e) {
            // Two concurrent FIRST settlements: one lost either uk_vch_idem or
            // uk_recon_account_date, and its transaction has rolled back, so the
            // winner is only visible from a fresh one. An ordinary retry never
            // reaches here — the service looks the key up before it writes.
            return upiSettlements.replayed(idempotencyKey)
                    .map(original -> ResponseEntity.status(HttpStatus.CONFLICT).body(original))
                    .orElseThrow(() -> e);
        }
    }

    // ------------------------------------------------------------------
    // #1 — the whole screen in one call
    // ------------------------------------------------------------------

    /**
     * Everything closeable tonight.
     *
     * <p>⚠ NO {@code {poolCode}} ON THE PATH. Artboard 9 is one screen showing
     * the recharge/ticket drawer in detail, then the water drawer and the safe,
     * then a projected "After closing — Safe" — so one call per pool would be
     * three round trips for one screen, and the safe has no preview of its own to
     * ask for (§1.1, settled 2026-11-02).
     */
    @GetMapping("/close-preview")
    public ClosePreviewResponse closePreview() {
        return closeService.preview();
    }

    // ------------------------------------------------------------------
    // #2 — the day close
    // ------------------------------------------------------------------

    /**
     * Records the count, posts the variance if any, sweeps the tills to
     * {@code CASH_SAFE}. One POST and one transaction (A1).
     *
     * <p>⚠ THE DUPLICATE-KEY RECOVERY IS HERE, AND IT HAS TO BE. The duplicate
     * throws and the whole transaction rolls back INCLUDING the
     * {@code acc_reconciliation} rows, so the stored close is not visible from
     * inside the aborted transaction. This catch runs after that rollback and
     * re-reads in a FRESH read-only transaction.
     *
     * <p><b>409, carrying the original close.</b> §1.1's code list says 409
     * "already closed for that date" and §1.3 says the service returns "the
     * original close rather than a second sweep"; both hold only if the 409
     * carries the record. The precedent is {@code RechargeController.java:593},
     * where a duplicate mobile answers 409 with the EXISTING customer, for the
     * same reason — a bare 409 leaves the operator stuck, here with a counted
     * drawer and no figures to compare it against.
     *
     * <p>⚠ A pre-check {@code SELECT} would be a race in itself: two clicks can
     * both read "not closed" before either writes. Insert-then-recover is correct
     * and needs no locking.
     */
    @PostMapping("/pools/{poolCode}/closes")
    public ResponseEntity<PoolCloseResponse> closePool(
            @PathVariable String poolCode,
            @Valid @RequestBody PoolCloseRequest request,
            Principal principal) {

        try {
            PoolCloseResponse closed =
                    closeService.closePool(poolCode, request, staffIdOf(principal));
            return ResponseEntity
                    .created(URI.create("/accounts/pools/" + poolCode + "/closes/"
                            + request.reconDate()))
                    .body(closed);

        } catch (DataIntegrityViolationException e) {
            // Only a close already on file turns into a 409; anything else is a
            // genuine integrity failure and is rethrown for the handler, which
            // must not report a constraint as an already-done.
            return closeService.replayedPoolClose(poolCode, request.reconDate())
                    .map(original -> ResponseEntity.status(HttpStatus.CONFLICT).body(original))
                    .orElseThrow(() -> e);
        }
    }

    // ------------------------------------------------------------------
    // #3 — the close history
    // ------------------------------------------------------------------

    /**
     * One account's closes, newest first. {@code from} and {@code to} are both
     * inclusive and both optional — a history screen opens before it knows any
     * dates.
     */
    @GetMapping("/{accountCode}/reconciliations")
    public Page<ReconciliationResponse> reconciliations(
            @PathVariable String accountCode,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return closeService.history(accountCode, from, to, page, size);
    }

    // ------------------------------------------------------------------
    // #4 — a single account's close
    // ------------------------------------------------------------------

    /**
     * Closes a reconcilable account that is not in a pool — {@code CASH_SAFE}, a
     * bank, a float — and, on a float, corrects its balance.
     *
     * <p>⚠ {@code CASH_SAFE} CLOSES HERE AND NOWHERE ELSE, although
     * {@code V21:331} seeds it into cash pool {@code SAFE}. The seed is what is
     * wrong — {@code adr/0002-cash-safe-closes-as-an-account-not-a-pool.md} — and
     * {@link LedgerCloseService#closePool} refuses {@code poolCode = SAFE} with a
     * 422 naming this endpoint.
     *
     * <p>Same 409-carrying-the-original replay shape as #2, for the same reason.
     */
    @PostMapping("/{accountCode}/reconciliations")
    public ResponseEntity<ReconciliationResponse> closeAccount(
            @PathVariable String accountCode,
            @Valid @RequestBody ReconciliationRequest request,
            Principal principal) {

        try {
            ReconciliationResponse closed =
                    closeService.closeAccount(accountCode, request, staffIdOf(principal));
            return ResponseEntity
                    .created(URI.create("/accounts/" + accountCode + "/reconciliations/"
                            + closed.reconId()))
                    .body(closed);

        } catch (DataIntegrityViolationException e) {
            return closeService.replayedAccountClose(accountCode, request.reconDate())
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
