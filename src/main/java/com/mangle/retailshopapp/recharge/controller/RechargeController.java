package com.mangle.retailshopapp.recharge.controller;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import com.mangle.retailshopapp.credit.repo.RcTxnHeaderRepository;
import com.mangle.retailshopapp.customer.model.CustomerDetails;
import com.mangle.retailshopapp.recharge.domain.FinancialYear;
import com.mangle.retailshopapp.recharge.domain.RechargeStatus;
import com.mangle.retailshopapp.recharge.domain.Outstanding;
import com.mangle.retailshopapp.recharge.domain.ServiceType;
import com.mangle.retailshopapp.recharge.model.RcOperator;
import com.mangle.retailshopapp.recharge.model.RcRecharge;
import com.mangle.retailshopapp.recharge.model.request.MarkCompleteRequest;
import com.mangle.retailshopapp.recharge.model.request.MarkFailedRequest;
import com.mangle.retailshopapp.recharge.model.request.CreateCustomerRequest;
import com.mangle.retailshopapp.recharge.model.request.RefundRechargeRequest;
import com.mangle.retailshopapp.recharge.model.request.RetryRechargeRequest;
import com.mangle.retailshopapp.recharge.model.request.SettleBasketRequest;
import com.mangle.retailshopapp.recharge.model.response.CounterConfigResponse;
import com.mangle.retailshopapp.recharge.model.response.CounterConfigResponse.OperatorConfig;
import com.mangle.retailshopapp.recharge.model.response.CounterConfigResponse.RouteConfig;
import com.mangle.retailshopapp.recharge.model.response.CreateCustomerRequestResult;
import com.mangle.retailshopapp.recharge.model.response.CreditBookRowResponse;
import com.mangle.retailshopapp.recharge.model.response.CreditCustomerResponse;
import com.mangle.retailshopapp.recharge.model.response.CreditStatementResponse;
import com.mangle.retailshopapp.recharge.model.response.DaySummaryResponse;
import com.mangle.retailshopapp.recharge.model.response.HistoryPageResponse;
import com.mangle.retailshopapp.recharge.model.response.NumberHintResponse;
import com.mangle.retailshopapp.recharge.model.response.PageResponse;
import com.mangle.retailshopapp.recharge.model.response.RefundResponse;
import com.mangle.retailshopapp.recharge.model.response.RetryRechargeResponse;
import com.mangle.retailshopapp.recharge.model.response.TvConnectionResponse;
import com.mangle.retailshopapp.recharge.model.response.RechargeResponse;
import com.mangle.retailshopapp.recharge.model.response.RechargeWorkItemResponse;
import com.mangle.retailshopapp.recharge.model.response.TransactionResponse;
import com.mangle.retailshopapp.recharge.exception.DuplicateRetryException;
import com.mangle.retailshopapp.recharge.repo.RechargeOpenItemsRepository;
import com.mangle.retailshopapp.recharge.service.CounterEntryService;
import com.mangle.retailshopapp.recharge.service.CreditLedgerService;
import com.mangle.retailshopapp.recharge.service.CustomerDirectoryService;
import com.mangle.retailshopapp.recharge.service.FinancialYears;
import com.mangle.retailshopapp.recharge.service.OperatorConfigService;
import com.mangle.retailshopapp.recharge.service.RechargeFailureResolutionService;
import com.mangle.retailshopapp.recharge.service.RechargeFailureResolutionService.Settlement;
import com.mangle.retailshopapp.recharge.service.RechargeFulfilmentService;
import com.mangle.retailshopapp.recharge.service.RechargeHistoryService;
import com.mangle.retailshopapp.recharge.service.RechargeResponseMapper;
import com.mangle.retailshopapp.recharge.service.RechargeSettlementService;
import com.mangle.retailshopapp.recharge.service.RechargeSettlementService.RequestedRecharge;
import com.mangle.retailshopapp.recharge.service.RechargeSettlementService.RequestedTender;
import com.mangle.retailshopapp.user.model.User;
import com.mangle.retailshopapp.user.repo.UserRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.security.Principal;

/**
 * The counter's HTTP surface (design-api.md §3.2).
 *
 * <p>⚠ NO {@code /v1} PREFIX. §9.2 names API versioning as explicitly rejected,
 * and the brief lists it among the three things S3 must not build. One client, one
 * deployment, both released together.
 *
 * <p>⚠ NO {@code @PreAuthorize} ANYWHERE. §3.5: requirement 9 means all staff see
 * all transactions and any staff member may extend credit, so there is NO per-row
 * authorisation. The path prefix {@code /recharge/**} is guarded once in
 * {@code SecurityConfig}, and the control on credit is the RECORD — who extended
 * it, when, and the note — taken from {@code Principal}.
 *
 * <p>⚠ NO {@code DELETE} ON ANYTHING (§3.2). The shop's book of record is
 * append-only, and {@code RcTxnHeaderService.deleteTransaction} is a precedent to
 * retire rather than extend. A mistake becomes a status change plus a ledger
 * correction.
 *
 * <p>No repository is injected here except the two read-only ones whose whole
 * purpose is a list projection — {@code MotorController:44,59,62} and
 * {@code CustomerApprovalController:31-37} are what §5.4 rule 3 is reacting to,
 * and {@code CustomerApprovalController.approveCustomer} writes three tables
 * straight from a controller with no service and no transaction. Nothing here
 * writes.
 *
 * <p><b>This slice ships 17 of §3.2's 18 live endpoints.</b> Everything the
 * counter, the worklist, history, the end-of-day read, resolving a failure and
 * the credit book's two reads.
 *
 * <p>⚠ §3.2 LISTS 19, AND #19 WAS WITHDRAWN on 2026-10-21 rather than left
 * unshipped. {@code POST /recharges/{id}/void} was built and it worked; it was
 * removed because {@code VOIDED} means "the sale should never have been
 * recorded", which is exactly the case where the customer was charged and the
 * money has to go back — and a void could not return it. It credited the amount
 * to {@code RCHG_PENDING} and left it there: the residual mechanism that was
 * supposed to settle it from there refuses any status that is not
 * {@code FAILED}, and {@code heldPending} has no date filter, so the stranded
 * amount would have sat on the day summary for good.
 * {@code COMPLETED → FAILED → REFUNDED} does the same job, posts the same
 * reversal, and both halves have screens. What is lost is the LABEL, not the
 * correction — {@code MarkFailedRequest} takes an optional reason, so "we keyed
 * it twice" is still recorded.
 *
 * <p>The other one left is #16, taking a payment against a tab, and it is
 * deferred deliberately rather than forgotten: design-ledger.md §6.3 makes
 * the LEDGER the authority for receivables from go-live and retires
 * {@code rc_credit_req} to a derived subledger, so a receipt should clear a
 * customer's {@code AR_*} legs oldest-first — each of which names its own
 * business. That allocation cannot be written yet, because
 * implementation-plan.md §1.2 states the ledger "is not authoritative for
 * recharge until the legacy writer is retired", and today it holds no legacy
 * debt at all. #16 belongs in S6, beside the opening balances that put it there.
 */
@RestController
@RequestMapping("/recharge")
@Validated
public class RechargeController {

    private final RechargeSettlementService settlement;
    private final RechargeFulfilmentService fulfilment;
    private final RechargeFailureResolutionService resolution;
    private final CounterEntryService entry;
    private final RechargeHistoryService historyService;
    private final CreditLedgerService credit;
    private final OperatorConfigService config;
    private final CustomerDirectoryService customers;
    private final RechargeResponseMapper mapper;
    private final RechargeOpenItemsRepository openItems;
    private final RcTxnHeaderRepository headers;
    private final FinancialYears financialYears;
    private final UserRepository users;
    private final Clock clock;

    public RechargeController(RechargeSettlementService settlement,
                              RechargeFulfilmentService fulfilment,
                              RechargeFailureResolutionService resolution,
                              CounterEntryService entry,
                              RechargeHistoryService historyService,
                              CreditLedgerService credit,
                              OperatorConfigService config,
                              CustomerDirectoryService customers,
                              RechargeResponseMapper mapper,
                              RechargeOpenItemsRepository openItems,
                              RcTxnHeaderRepository headers,
                              FinancialYears financialYears,
                              UserRepository users,
                              Clock clock) {
        this.settlement = settlement;
        this.fulfilment = fulfilment;
        this.resolution = resolution;
        this.entry = entry;
        this.historyService = historyService;
        this.credit = credit;
        this.config = config;
        this.customers = customers;
        this.mapper = mapper;
        this.openItems = openItems;
        this.headers = headers;
        this.financialYears = financialYears;
        this.users = users;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // #1 — one startup call
    // ------------------------------------------------------------------

    /**
     * Everything the counter needs before the first keystroke.
     *
     * <p>One call rather than one per operator, because the entry screen needs
     * the whole catalogue to render its chips — and because the presets differ per
     * operator (log §14.10), so a lazy per-operator fetch would cost a round trip
     * on every operator change.
     */
    @GetMapping("/config")
    public CounterConfigResponse config() {
        Map<String, List<java.math.BigDecimal>> presets = config.allPresets();
        FinancialYear fy = financialYears.current();

        List<OperatorConfig> operators = config.selectableOperators().stream()
                .map(o -> new OperatorConfig(
                        o.getOperatorCode(),
                        o.getDisplayName(),
                        o.getRechargeKind().name(),
                        o.getDefaultRouteCode(),
                        // Empty is an ANSWER for TV and for VI, not missing data.
                        presets.getOrDefault(o.getOperatorCode(), List.of())))
                .toList();

        List<RouteConfig> routes = config.activeRoutes().stream()
                .map(r -> new RouteConfig(r.getCode(), r.getDescription()))
                .toList();

        return new CounterConfigResponse(
                operators,
                routes,
                config.creditWarningDays(),
                financialYears.startMonth(),
                fy.label(),
                config.changeDisplayThreshold(),
                config.defaultSurchargeFor(com.mangle.retailshopapp.recharge.domain.ServiceType.TV),
                config.defaultSurchargeFor(
                        com.mangle.retailshopapp.recharge.domain.ServiceType.MOBILE));
    }

    // ------------------------------------------------------------------
    // #6 — the one atomic write
    // ------------------------------------------------------------------

    /**
     * Settles a basket.
     *
     * <p>⚠ THE DUPLICATE-KEY RECOVERY IS HERE, AND IT HAS TO BE. §7.1: the
     * duplicate throws and the whole transaction rolls back INCLUDING the key
     * row, so the stored result is not visible from inside the aborted
     * transaction. This catch runs after that rollback and looks the key up in a
     * FRESH read-only transaction, returning 200 with the original payment.
     *
     * <p>A pre-check {@code SELECT} before inserting would be a race in itself.
     * Insert-then-recover is correct and needs no locking.
     */
    @PostMapping("/transactions")
    public ResponseEntity<TransactionResponse> settle(
            @RequestHeader("Idempotency-Key")
            @NotBlank(message = "an Idempotency-Key header is required")
            @Size(max = 80, message = "an Idempotency-Key is at most 80 characters")
            String idempotencyKey,
            @Valid @RequestBody SettleBasketRequest request,
            Principal principal) {

        Integer staffUserId = staffIdOf(principal);

        List<RequestedRecharge> recharges = request.recharges().stream()
                .map(r -> new RequestedRecharge(r.serviceType(), r.number(), r.operatorCode(),
                        r.amount(), r.surcharge()))
                .toList();
        List<RequestedTender> tenders = request.settlementLines().stream()
                .map(t -> new RequestedTender(t.method(), t.amount(), t.amountTendered(),
                        t.reference(), t.customerId(), t.note()))
                .toList();

        try {
            var settled = settlement.settle(recharges, tenders, staffUserId, idempotencyKey);
            TransactionResponse body = toResponse(settled, principal);
            return ResponseEntity
                    .created(URI.create("/recharge/transactions/" + settled.paymentId()))
                    .body(body);
        } catch (DataIntegrityViolationException e) {
            // The replay path. Only a key that is already on file turns into a
            // 200; anything else is a genuine integrity failure and is rethrown
            // for the handler, which must not report a constraint as a success.
            return settlement.replayedPaymentId(idempotencyKey)
                    .map(paymentId -> ResponseEntity.ok(transaction(paymentId, principal)))
                    .orElseThrow(() -> e);
        }
    }

    /**
     * The whole visit (#7): the payment, its recharges with current statuses, and
     * its tenders.
     *
     * <p>Not year-scoped, and it must not be: a payment id is an exact reference,
     * and refusing to show last year's visit because the picker sits on this year
     * is the stored-mode failure §9.3 forbids.
     *
     * <p>This is also the REPLAY path's return. A duplicate
     * {@code Idempotency-Key} resolves to a payment id and comes back through
     * here, which is why it has to be a real read rather than a stub — a partial
     * response would make a replayed settle look like a successful one with no
     * recharges on it.
     */
    @GetMapping("/transactions/{paymentId}")
    public TransactionResponse transaction(@PathVariable int paymentId, Principal principal) {
        var visit = historyService.visit(paymentId);
        return mapper.toVisit(visit, principal == null ? null : principal.getName(),
                LocalDate.now(clock));
    }

    // ------------------------------------------------------------------
    // #8, #11 — the worklist and the failures
    // ------------------------------------------------------------------

    /**
     * The worklist (#8). NEVER financial-year filtered (requirement 11).
     *
     * <p>The repository it reads takes no date parameter at all, which is §7.1(d)'s
     * structural enforcement rather than a convention here.
     *
     * <p>It is normally EMPTY: 468 of 681 active hours held exactly one recharge
     * and the busiest hour in six months held 7 (log §14.10). A client should
     * present it as a leftover tray, not a queue.
     */
    @GetMapping("/recharges/awaiting")
    public PageResponse<RechargeWorkItemResponse> awaiting(
            @RequestParam(required = false) @Size(max = 20) String operatorCode,
            @RequestParam(required = false) @Size(max = 20) String routeCode,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        Page<RcRecharge> found = openItems.findAwaiting(
                blankToNull(operatorCode), blankToNull(routeCode), PageRequest.of(page, size));
        return toWorkItems(found);
    }

    /**
     * Unresolved failures (#11). Also never year-filtered.
     *
     * <p>One status, not a pair: a resolved failure has MOVED to {@code RETRIED}
     * or {@code REFUNDED}, so {@code STATUS_CODE = 'FAILED'} IS the unresolved set
     * (design-domain.md §5.2).
     */
    @GetMapping("/recharges/failures")
    public PageResponse<RechargeWorkItemResponse> failures(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        return toWorkItems(openItems.findUnresolvedFailures(PageRequest.of(page, size)));
    }

    // ------------------------------------------------------------------
    // #9, #10, #19 — doing the recharges
    // ------------------------------------------------------------------

    /**
     * Mark done (#9) — the moment it counts toward takings and the float is
     * consumed.
     *
     * <p>Scoped to ONE recharge. §4.4 is explicit that there is deliberately no
     * bulk complete-all: it would create a transaction in which recharge 3 failing
     * rolls back the Completed status of recharges 1 and 2, which is the exact
     * opposite of the requirement. Three at once means three calls and three
     * outcomes.
     */
    @PostMapping("/recharges/{rechargeId}/complete")
    public RechargeResponse complete(@PathVariable long rechargeId,
                                     @Valid @RequestBody(required = false)
                                     MarkCompleteRequest request,
                                     Principal principal) {
        MarkCompleteRequest body = request == null
                ? new MarkCompleteRequest(null, null) : request;
        RcRecharge completed = fulfilment.markCompleted(rechargeId, body.vendorRef(),
                blankToNull(body.routeCode()), staffIdOf(principal));
        return mapper.toRecharge(completed);
    }

    /** Mark failed (#10). Nothing posts; the money stays in {@code RCHG_PENDING}. */
    @PostMapping("/recharges/{rechargeId}/fail")
    public RechargeResponse fail(@PathVariable long rechargeId,
                                 @Valid @RequestBody(required = false) MarkFailedRequest request,
                                 Principal principal) {
        String reason = request == null ? null : request.reason();
        return mapper.toRecharge(fulfilment.markFailed(rechargeId, reason, staffIdOf(principal)));
    }

    // ------------------------------------------------------------------
    // #14, #15 — the credit book (requirements 6, 9)
    // ------------------------------------------------------------------

    /**
     * Who owes the shop money, oldest debt first (#14).
     *
     * <p>⚠ ACROSS EVERY BUSINESS, and never year-filtered. log §14.8: scoped to
     * recharge, someone owing ₹2,000 on water and ₹49 on recharge would show as
     * ₹49, so the {@code warn} flag — the entire reason this panel exists —
     * would understate the exposure while looking authoritative. §9.3 names what
     * a year filter costs: 33 of 34 open balances vanish and staff stop chasing
     * debt.
     *
     * <p>{@code minOutstanding} is ABSENT by default rather than zero, and the
     * two differ: absent keeps the two customers who are in credit, zero would
     * drop them.
     */
    @GetMapping("/credit/customers")
    public PageResponse<CreditBookRowResponse> creditBook(
            @RequestParam(required = false) @PositiveOrZero BigDecimal minOutstanding,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        LocalDate today = LocalDate.now(clock);
        int warnDays = config.creditWarningDays();

        List<CreditBookRowResponse> rows = credit.creditBook(minOutstanding,
                        PageRequest.of(page, size)).stream()
                .map(r -> CreditBookRowResponse.from(r, today, warnDays))
                .toList();

        return PageResponse.of(rows, page, size, credit.creditBookSize(minOutstanding));
    }

    /**
     * One person's tab, newest first, from every business (#15).
     *
     * <p>The header figures come from the SAME {@code positionOf} the basket
     * screen uses, so the outstanding a staff member sees here cannot disagree
     * with the one they saw when taking the sale. Two formulas for one number is
     * how they come to differ.
     */
    @GetMapping("/credit/customers/{customerId}")
    public CreditStatementResponse creditStatement(
            @PathVariable int customerId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {

        // Resolved first: an unknown customer or a ledger account is a 404 here
        // rather than an empty statement, because an empty statement reads as
        // "owes nothing" and a bank pseudo-customer's clearing balance is not a
        // tab at all (§2.6).
        CustomerDetails customer = customers.requireCreditable(customerId);

        var lines = credit.statement(customerId, PageRequest.of(page, size));
        PageResponse<CreditStatementResponse.StatementLineResponse> body = PageResponse.of(
                CreditStatementResponse.linesOf(lines), page, size,
                credit.statementSize(customerId));

        return CreditStatementResponse.from(customerId, customer.getCustomerName(),
                customer.getContactNum(), credit.positionOf(customerId),
                LocalDate.now(clock), config.creditWarningDays(), body);
    }

    // ------------------------------------------------------------------
    // #12, #13 — resolving a failure (requirement 5)
    // ------------------------------------------------------------------

    /**
     * Retry a failed recharge under the ORIGINAL payment (#12).
     *
     * <p>⚠ NO {@code Idempotency-Key} HEADER, and that is §7.2's decision rather
     * than an omission: #12 "is guarded by the unique retry key". V29 adds the
     * key V12 left non-unique, so a double-click reaches the database and is
     * refused there as a 409 rather than being deduplicated in the application.
     *
     * <p>The difference, in whichever direction it falls, is settled in THIS
     * call. §3.3: "which is what keeps it in one transaction."
     */
    @PostMapping("/recharges/{rechargeId}/retry")
    public ResponseEntity<RetryRechargeResponse> retry(
            @PathVariable long rechargeId,
            @Valid @RequestBody RetryRechargeRequest request,
            Principal principal) {

        var difference = request.differenceSettlement() == null ? null
                : new Settlement(request.differenceSettlement().mode(),
                        request.differenceSettlement().customerId(),
                        request.differenceSettlement().note());
        var topUp = request.topUp() == null ? null
                : new Settlement(null, request.topUp().method(),
                        request.topUp().customerId(), request.topUp().note());

        try {
            var retried = resolution.retry(rechargeId, request.amount(), difference, topUp,
                    staffIdOf(principal));
            RetryRechargeResponse body = mapper.toRetry(retried,
                    customerOf(retried.creditPosition(), retried.retry().getTxnId()),
                    LocalDate.now(clock));
            return ResponseEntity
                    .created(URI.create("/recharge/recharges/" + retried.retry().getRechargeId()))
                    .body(body);
        } catch (DataIntegrityViolationException e) {
            // uq_rech_retry. The row that lost the race is the one that gets
            // this, and the answer is "someone already retried it" rather than a
            // constraint name — §7's threat 3 chose the key precisely so this
            // decision is the database's and not a read-then-write in the app.
            throw new DuplicateRetryException(rechargeId);
        }
    }

    /**
     * Refund a failed recharge (#13).
     *
     * <p>Cash back, a UPI transfer sent back, or the customer's tab reduced —
     * log §7.4's table as amended on 2026-10-21, of which §3.2 names the first
     * and the last. Which are AVAILABLE is a fact about the original payment and
     * not a choice: the money goes home by a rail it actually arrived on, so a
     * sale that went out on credit collected no cash and has none to hand back.
     *
     * <p>⚠ {@code note} is MANDATORY when {@code mode = UPI} and carries the UPI
     * transaction reference — a 422 {@code UPI_REQUIRES_REFERENCE}. It is also
     * the first mode that reads {@code note} at all: until 2026-10-21 the field
     * was accepted here and dropped in the service.
     */
    @PostMapping("/recharges/{rechargeId}/refund")
    public ResponseEntity<RefundResponse> refund(
            @PathVariable long rechargeId,
            @Valid @RequestBody RefundRechargeRequest request,
            Principal principal) {

        var refunded = resolution.refund(rechargeId, request.amount(), request.mode(),
                request.customerId(), request.note(), staffIdOf(principal));
        RefundResponse body = mapper.toRefund(refunded,
                refunded.customerId() == null ? null
                        : customers.requireCreditable(refunded.customerId()),
                LocalDate.now(clock));
        return ResponseEntity
                .created(URI.create("/recharge/recharges/" + rechargeId))
                .body(body);
    }

    // ------------------------------------------------------------------
    // #2, #3 — the entry step
    // ------------------------------------------------------------------

    /**
     * Operator and route prefill from this number's last recharge (#2).
     *
     * <p>Earns its place on measurement: 88.5% of mobile and 93.6% of TV
     * recharges go to a number seen before (log §14.10). Returns 200 with
     * {@code known:false} rather than a 404 for an unseen number — which is EVERY
     * number until V15's backfill runs, so the commonest case on day one must not
     * look like a failure.
     */
    @GetMapping("/number-hints/{serviceType}/{number}")
    public NumberHintResponse numberHint(
            @PathVariable ServiceType serviceType,
            @PathVariable @NotBlank @Size(max = 20) String number) {
        return NumberHintResponse.from(entry.hintFor(serviceType, number.trim()));
    }

    /** TV connection search by name, contact or connection number (#3). */
    @GetMapping("/tv-connections")
    public List<TvConnectionResponse> tvConnections(
            @RequestParam @Size(min = 2, max = 64) String query,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return entry.searchConnections(query, limit).stream()
                .map(TvConnectionResponse::from)
                .toList();
    }

    // ------------------------------------------------------------------
    // #4, #5 — the credit customer
    // ------------------------------------------------------------------

    /**
     * Customer search for a credit sale (#4).
     *
     * <p>⚠ ALL customers, not water-only, and ledger accounts excluded. And the
     * outstanding figure is CROSS-BUSINESS (log §14.8): scoped to recharge,
     * someone owing ₹2,000 on water and ₹49 on recharge would show as ₹49, so the
     * warn flag — the entire reason this panel exists — would understate the
     * exposure while looking authoritative.
     */
    @GetMapping("/customers")
    public List<CreditCustomerResponse> searchCustomers(
            @RequestParam @Size(min = 2, max = 64) String query,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        LocalDate today = LocalDate.now(clock);
        int warnDays = config.creditWarningDays();

        return customers.search(query, limit).stream()
                .map(c -> CreditCustomerResponse.from(c, credit.positionOf(c.getCustId()),
                        today, warnDays))
                .toList();
    }

    /**
     * Create a walk-in inline (#5).
     *
     * <p>A mobile already on file is a 409 carrying the EXISTING customer id, so
     * the staff member can use the person who is already there rather than
     * creating a duplicate someone has to merge later (§3.2). A bare 409 would
     * leave them stuck with a customer in front of them.
     */
    @PostMapping("/customers")
    public ResponseEntity<?> createCustomer(@Valid @RequestBody CreateCustomerRequest request) {
        var existing = customers.findByMobile(request.mobile());
        if (existing.isPresent()) {
            CustomerDetails c = existing.get();
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    new CreateCustomerRequestResult(c.getCustId(), c.getCustomerName(),
                            c.getContactNum()));
        }

        CustomerDetails created = customers.createWalkIn(request.firstName(), request.lastName(),
                request.mobile(), LocalDateTime.now(clock));

        return ResponseEntity
                .created(URI.create("/recharge/customers/" + created.getCustId()))
                .body(CreditCustomerResponse.from(created, Outstanding.clear(),
                        LocalDate.now(clock), config.creditWarningDays()));
    }

    // ------------------------------------------------------------------
    // #17, #18 — history and end of day
    // ------------------------------------------------------------------

    /**
     * History search (#17).
     *
     * <p>⚠ {@code status} HERE IS THE STORED STATUS, not the wire pair. Filtering
     * for unresolved failures is {@code status=FAILED}; a retried one is
     * {@code status=RETRIED}. The response still SURFACES the pair, because §3.3's
     * contract has two fields — see {@code FailureResolution}.
     */
    @GetMapping("/history")
    public HistoryPageResponse history(
            @RequestParam(required = false) @Pattern(regexp = "^\\d{4}-\\d{2}$",
                    message = "a financial year looks like 2026-27") String financialYear,
            @RequestParam(required = false) @Size(max = 20) String number,
            @RequestParam(required = false) @Size(max = 20) String operatorCode,
            @RequestParam(required = false) @Size(max = 20) String routeCode,
            @RequestParam(required = false) RechargeStatus status,
            @RequestParam(required = false) Integer staffId,
            @RequestParam(required = false) @Size(max = 64) String reference,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        var result = historyService.search(financialYear, number, operatorCode, routeCode,
                status, staffId, reference, PageRequest.of(page, size));

        FinancialYear fy = result.financialYear();
        return new HistoryPageResponse(
                fy.label(),
                fy.startDate(),
                fy.endDate(),
                historyService.dataAvailableFrom(),
                PageResponse.of(result.page().map(mapper::toRecharge)));
    }

    /**
     * End of day (#18) — what the slice's gate compares to the drawer.
     *
     * <p>A read, not an action. §3.2: there is deliberately no
     * {@code POST /recharge/day-close}, because requirement 8 asks for a warning
     * and never a block — a day-close resource would create closed/open day
     * state, a reopen endpoint and a rule about posting into a closed day.
     */
    @GetMapping("/day-summary")
    public DaySummaryResponse daySummary(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        var summary = historyService.daySummary(date);

        return new DaySummaryResponse(
                summary.date(),
                summary.takings().stream()
                        .map(t -> new DaySummaryResponse.TakingsRow(t.operatorCode(),
                                t.routeCode(), t.count(), t.amount(), t.surcharge()))
                        .toList(),
                summary.totalTakings(),
                summary.totalSurcharge(),
                summary.totalCollected(),
                summary.cashCollectedToday(),
                summary.cashPaidOutToday(),
                summary.tenderMix().entrySet().stream().collect(java.util.stream.Collectors.toMap(
                        e -> e.getKey().name(), Map.Entry::getValue)),
                summary.awaitingCount(),
                summary.unresolvedFailureCount(),
                summary.heldPending());
    }

    // ------------------------------------------------------------------

    private PageResponse<RechargeWorkItemResponse> toWorkItems(Page<RcRecharge> found) {
        Map<String, RcOperator> byCode = mapper.operatorsByCode();
        List<RechargeWorkItemResponse> rows = found.getContent().stream()
                .map(r -> mapper.toWorkItem(r, byCode))
                .toList();
        return PageResponse.of(rows, found.getNumber(), found.getSize(),
                found.getTotalElements());
    }

    private TransactionResponse toResponse(RechargeSettlementService.Settled settled,
                                           Principal principal) {
        CustomerDetails creditCustomer = null;
        if (settled.plan().hasCredit()) {
            creditCustomer = customers.requireCreditable(
                    settled.plan().creditLine().customerId());
        }
        return mapper.toTransaction(settled, principal == null ? null : principal.getName(),
                creditCustomer, LocalDate.now(clock));
    }

    /**
     * The staff member's {@code rc_user.id}, from the authenticated principal.
     *
     * <p>Never from the request body. §3.5's whole authorisation model rests on
     * this being the authenticated identity: "the control is the record —
     * {@code extendedBy}, {@code extendedAt}, {@code note} taken from
     * {@code Principal}." A client-supplied staff id would make the record
     * worthless.
     *
     * <p>Null-tolerant so the module is testable without a security context, and
     * because {@code CRE_BY} is genuinely nullable — every backfilled historical
     * row has none.
     */
    private Integer staffIdOf(Principal principal) {
        if (principal == null) {
            return null;
        }
        // findByUsername returns the entity or null rather than an Optional, which
        // is the existing repository's signature and not this slice's to change.
        User user = users.findByUsername(principal.getName());
        return user == null ? null : user.getId();
    }

    /**
     * The customer a resolution touched, for the response's credit summary.
     *
     * <p>Null whenever no tab moved, which is the common case: a cash refund
     * names nobody. Read from the payment's header rather than from the request,
     * because on a {@code CREDIT_REDUCTION} the service derives the customer
     * from the original credit line and the request's opinion is ignored.
     */
    private CustomerDetails customerOf(Outstanding position, Integer paymentId) {
        if (position == null || paymentId == null) {
            return null;
        }
        return headers.findById(paymentId)
                .map(h -> h.getCustId() == null ? null : customers.requireCreditable(h.getCustId()))
                .orElse(null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
