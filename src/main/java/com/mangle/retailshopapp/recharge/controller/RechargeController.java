package com.mangle.retailshopapp.recharge.controller;

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
import com.mangle.retailshopapp.recharge.model.request.SettleBasketRequest;
import com.mangle.retailshopapp.recharge.model.request.VoidRechargeRequest;
import com.mangle.retailshopapp.recharge.model.response.CounterConfigResponse;
import com.mangle.retailshopapp.recharge.model.response.CounterConfigResponse.OperatorConfig;
import com.mangle.retailshopapp.recharge.model.response.CounterConfigResponse.RouteConfig;
import com.mangle.retailshopapp.recharge.model.response.CreateCustomerRequestResult;
import com.mangle.retailshopapp.recharge.model.response.CreditCustomerResponse;
import com.mangle.retailshopapp.recharge.model.response.DaySummaryResponse;
import com.mangle.retailshopapp.recharge.model.response.HistoryPageResponse;
import com.mangle.retailshopapp.recharge.model.response.NumberHintResponse;
import com.mangle.retailshopapp.recharge.model.response.PageResponse;
import com.mangle.retailshopapp.recharge.model.response.TvConnectionResponse;
import com.mangle.retailshopapp.recharge.model.response.RechargeResponse;
import com.mangle.retailshopapp.recharge.model.response.RechargeWorkItemResponse;
import com.mangle.retailshopapp.recharge.model.response.TransactionResponse;
import com.mangle.retailshopapp.recharge.repo.RechargeOpenItemsRepository;
import com.mangle.retailshopapp.recharge.service.CounterEntryService;
import com.mangle.retailshopapp.recharge.service.CreditLedgerService;
import com.mangle.retailshopapp.recharge.service.CustomerDirectoryService;
import com.mangle.retailshopapp.recharge.service.FinancialYears;
import com.mangle.retailshopapp.recharge.service.OperatorConfigService;
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
 * <p><b>This slice ships 15 of §3.2's 19 endpoints.</b> Everything the counter,
 * the worklist, history and the end-of-day read need. The four left are the
 * failure-resolution pair (#12 retry, #13 refund) and the credit book (#14-#16),
 * each listed in the PR rather than left to be discovered.
 */
@RestController
@RequestMapping("/recharge")
@Validated
public class RechargeController {

    private final RechargeSettlementService settlement;
    private final RechargeFulfilmentService fulfilment;
    private final CounterEntryService entry;
    private final RechargeHistoryService historyService;
    private final CreditLedgerService credit;
    private final OperatorConfigService config;
    private final CustomerDirectoryService customers;
    private final RechargeResponseMapper mapper;
    private final RechargeOpenItemsRepository openItems;
    private final FinancialYears financialYears;
    private final UserRepository users;
    private final Clock clock;

    public RechargeController(RechargeSettlementService settlement,
                              RechargeFulfilmentService fulfilment,
                              CounterEntryService entry,
                              RechargeHistoryService historyService,
                              CreditLedgerService credit,
                              OperatorConfigService config,
                              CustomerDirectoryService customers,
                              RechargeResponseMapper mapper,
                              RechargeOpenItemsRepository openItems,
                              FinancialYears financialYears,
                              UserRepository users,
                              Clock clock) {
        this.settlement = settlement;
        this.fulfilment = fulfilment;
        this.entry = entry;
        this.historyService = historyService;
        this.credit = credit;
        this.config = config;
        this.customers = customers;
        this.mapper = mapper;
        this.openItems = openItems;
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

    /**
     * Void a completed sale (#19).
     *
     * <p>⚠ THE REVERSING VOUCHER IS NOT POSTED YET. The status moves — which is
     * what drops the sale out of takings, since that query filters on
     * {@code COMPLETED} — but the correcting entry needs
     * {@code LedgerCorrectionRules} wired to a caller, which S2 left outstanding.
     * Until then the ledger overstates income after a void, so this endpoint must
     * not be put in front of staff. Raised in the PR; said here so nobody wires a
     * button to it first.
     */
    @PostMapping("/recharges/{rechargeId}/void")
    public RechargeResponse voidRecharge(@PathVariable long rechargeId,
                                         @Valid @RequestBody VoidRechargeRequest request,
                                         Principal principal) {
        return mapper.toRecharge(
                fulfilment.voidRecharge(rechargeId, request.reason(), staffIdOf(principal)));
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
