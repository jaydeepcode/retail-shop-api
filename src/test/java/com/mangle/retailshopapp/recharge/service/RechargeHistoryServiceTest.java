package com.mangle.retailshopapp.recharge.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.mangle.retailshopapp.accounting.LedgerPostingService;
import com.mangle.retailshopapp.config.ClockConfig;
import com.mangle.retailshopapp.recharge.domain.RechargeStatus;
import com.mangle.retailshopapp.recharge.domain.ServiceType;
import com.mangle.retailshopapp.recharge.exception.PaymentNotFound;
import com.mangle.retailshopapp.recharge.service.RechargeSettlementService.RequestedRecharge;
import com.mangle.retailshopapp.recharge.service.RechargeSettlementService.RequestedTender;
import com.mangle.retailshopapp.settlement.SettlementMethod;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * History, the entry-step reads, and the day summary the slice's gate depends on.
 *
 * <p>implementation-plan.md:125 states that gate: "run real recharges on it
 * alongside the legacy app, and compare the day summary to the drawer." So the
 * assertions here are about the two rules that make that comparison mean
 * something — takings counting {@code COMPLETED} only, and the held-pending
 * figure coming from the ledger rather than from a status join.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({RechargeHistoryService.class, CounterEntryService.class,
         RechargeSettlementService.class, RechargeFulfilmentService.class,
         RechargeLedgerPostings.class, LedgerPostingService.class,
         OperatorConfigService.class, CustomerDirectoryService.class,
         CreditLedgerService.class, SettingService.class, FinancialYears.class,
         ClockConfig.class})
@DisplayName("History, entry reads, and the day summary")
class RechargeHistoryServiceTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.43")
            .withDatabaseName("recharge")
            .withCommand("--log-bin-trust-function-creators=1")
            .withInitScript("db/legacy-baseline.sql");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("spring.flyway.baseline-version", () -> "8");
        registry.add("spring.flyway.target", () -> "28");
        registry.add("spring.flyway.out-of-order", () -> "false");
    }

    @Autowired
    private RechargeHistoryService history;

    @Autowired
    private CounterEntryService entry;

    @Autowired
    private RechargeSettlementService settlement;

    @Autowired
    private RechargeFulfilmentService fulfilment;

    @Autowired
    private EntityManager em;

    private int staffUserId;

    @BeforeEach
    void seedStaff() {
        staffUserId = insertStaffUser();
    }

    // ------------------------------------------------------------------
    // The day summary — what the gate reads
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the day summary")
    class Summary {

        @Test
        @DisplayName("an empty day is zero everywhere, not an error")
        void emptyDay() {
            // Day one, before any sale. The close screen runs daily and must
            // render this rather than treat it as a missing figure.
            var summary = history.daySummary(LocalDate.of(2026, 10, 13));

            assertEquals(List.of(), summary.takings());
            assertEquals(new BigDecimal("0.00"), summary.totalTakings());
            assertEquals(new BigDecimal("0.00"), summary.heldPending());
            assertEquals(0, summary.awaitingCount());
            assertEquals(0, summary.unresolvedFailureCount());
        }

        @Test
        @DisplayName("⚠ takings count COMPLETED only, so a settled-not-done sale is NOT takings")
        void takingsAreCompletedOnly() {
            // The rule the whole gate rests on (requirement 6,
            // design-domain.md §5.2). A sale taken but not yet put through the
            // operator app is money in the drawer that is NOT yet takings.
            settle("BSNL", "147.00", "key-sum-1");

            var before = history.daySummary(LocalDate.now());
            assertEquals(new BigDecimal("0.00"), before.totalTakings(),
                    "cash is in the drawer, but the recharge has not happened");
            assertEquals(1, before.awaitingCount());
            assertEquals(new BigDecimal("147.00"), before.heldPending(),
                    "and the money shows as held, which is what explains the gap");
        }

        @Test
        @DisplayName("completing it moves the money from held to takings")
        void completionMovesIt() {
            var settled = settle("BSNL", "147.00", "key-sum-2");
            fulfilment.markCompleted(settled.recharges().get(0).getRechargeId(),
                    null, null, staffUserId);

            var after = history.daySummary(LocalDate.now());

            assertEquals(new BigDecimal("147.00"), after.totalTakings());
            assertEquals(0, after.awaitingCount());
            assertEquals(new BigDecimal("0.00"), after.heldPending(),
                    "RCHG_PENDING is clear once the recharge has happened");
            assertEquals(1, after.takings().size());
            assertEquals("BSNL", after.takings().get(0).operatorCode());
            assertEquals("A1TOPUP", after.takings().get(0).routeCode());
        }

        @Test
        @DisplayName("⚠ a failed recharge leaves the drawer over, and heldPending says by how much")
        void failureExplainsTheGap() {
            // §7.1(c): "cash in the drawer will not equal takings whenever a
            // recharge has failed and not yet been refunded. That is correct —
            // the money is held pending — and it is exactly what the end-of-day
            // check should warn about." The warning reads "Rs X held against N
            // unresolved recharges", never "till mismatch".
            var done = settle("ARTL", "199.00", "key-sum-3");
            fulfilment.markCompleted(done.recharges().get(0).getRechargeId(),
                    null, null, staffUserId);

            var failed = settle("RJIO", "299.00", "key-sum-4");
            fulfilment.markFailed(failed.recharges().get(0).getRechargeId(),
                    "operator declined", staffUserId);

            var summary = history.daySummary(LocalDate.now());

            assertEquals(new BigDecimal("199.00"), summary.totalTakings(),
                    "only the completed one counts");
            assertEquals(new BigDecimal("498.00"),
                    summary.tenderMix().get(SettlementMethod.CASH),
                    "but both baskets were paid for, so the drawer holds 498");
            assertEquals(new BigDecimal("299.00"), summary.heldPending(),
                    "and the 299 difference is readable as an account balance");
            assertEquals(1, summary.unresolvedFailureCount());

            // The identity the close screen can actually show.
            assertEquals(summary.tenderMix().get(SettlementMethod.CASH),
                    summary.totalTakings().add(summary.heldPending()),
                    "drawer = takings + held, exactly");
        }

        @Test
        @DisplayName("⚠ the till reconciles from the LEDGER and the tender rows independently")
        void tillReconcilesFromTwoSources() {
            // The replacement for two wrong identities. cashCollectedToday is the
            // day's NET CASH_RECHARGE movement read from the ledger;
            // tenderMix[CASH] is today's cash tender rows read from
            // rc_txn_payment; cashPaidOutToday is the credit side alone. Three
            // readings, two of them independent, and the identity between them
            // is what makes this a real check rather than a restatement:
            //
            //     cashCollectedToday = tenderMix[CASH] - cashPaidOutToday
            //
            // ⚠ THE THIRD TERM IS WHY THIS ASSERTS THE IDENTITY RATHER THAN
            // EQUALITY. Nothing is refunded on this fixture, so the two-term
            // form would pass here and break the first time #13 is used — money
            // going out cannot be a tender row at all (ck_txnpay_amount CHECK
            // (AMOUNT > 0)). The day with a refund on it is asserted in
            // RechargeFailureResolutionServiceTest, where one can be made.
            var completed = settle(ServiceType.TV, "DISHTV", "240.00", "key-till-1");
            fulfilment.markCompleted(completed.recharges().get(0).getRechargeId(),
                    null, null, staffUserId);
            settle(ServiceType.MOBILE, "ARTL", "199.00", "key-till-2");

            var summary = history.daySummary(LocalDate.now());

            assertEquals(summary.tenderMix().get(SettlementMethod.CASH)
                            .subtract(summary.cashPaidOutToday()),
                    summary.cashCollectedToday(),
                    "the ledger and the tender rows must report the same cash");
            assertEquals(new BigDecimal("0.00"), summary.cashPaidOutToday(),
                    "nothing went back over the counter on this fixture");
            assertTrue(summary.cashCollectedToday().signum() > 0);
        }

        @Test
        @DisplayName("⚠ a UPI sale does NOT enter the till figure, though it DOES hold money")
        void upiDoesNotEnterTheTill() {
            // WHY THE OLD IDENTITY WAS WRONG, case one. RCHG_PENDING is debited
            // from UPI_CLEARING and AR_RECHARGE as well as from CASH_RECHARGE, so
            // a settled-not-completed UPI sale inflates heldPending while no cash
            // entered the drawer. `drawer = collected + held` therefore could not
            // balance, and it only ever looked right because every test used cash.
            settlement.settle(
                    List.of(new RequestedRecharge(ServiceType.MOBILE, "9000000002", "ARTL",
                            new BigDecimal("199.00"), null)),
                    List.of(new RequestedTender(SettlementMethod.UPI, new BigDecimal("199.00"),
                            null, "UPI/1", null, null)),
                    staffUserId, "key-till-upi");

            var summary = history.daySummary(LocalDate.now());

            assertEquals(new BigDecimal("0.00"), summary.cashCollectedToday(),
                    "no cash was taken, so the till figure is zero");
            assertNull(summary.tenderMix().get(SettlementMethod.CASH),
                    "and there is no CASH key at all");
            assertEquals(new BigDecimal("199.00"), summary.heldPending(),
                    "but the money IS held - held is all-tender, which is why it "
                            + "cannot be a term in the till equation");
        }

        @Test
        @DisplayName("⚠ heldPending is ALL-TIME, so it survives a day boundary and the till figure does not")
        void heldPendingIsAllTime() {
            // WHY THE OLD IDENTITY WAS WRONG, case two. heldPending is the whole
            // RCHG_PENDING balance with no date filter - deliberately, because
            // open exposure is open regardless of when it was taken (§7.1(d)
            // forbids a date parameter on the open-items repository). But that
            // makes it incompatible with the day-scoped takings it was added to:
            // anything left unresolved overnight corrupts every later day.
            //
            // Shown by asking for ANOTHER day rather than by back-dating a
            // voucher. The first attempt at this test tried the latter and V23's
            // trigger refused it outright - "acc_voucher is immutable once
            // sealed; post a correcting JOURNAL instead" - which is the
            // immutability guarantee doing its job, and a neat demonstration that
            // the ledger cannot be quietly reshaped even by its own test suite.
            settle(ServiceType.MOBILE, "ARTL", "199.00", "key-till-stale");

            var today = history.daySummary(LocalDate.now());
            assertEquals(new BigDecimal("199.00"), today.cashCollectedToday());
            assertEquals(new BigDecimal("199.00"), today.heldPending());

            // Tomorrow: no cash was taken, but the money is still owed.
            var tomorrow = history.daySummary(LocalDate.now().plusDays(1));

            assertEquals(new BigDecimal("0.00"), tomorrow.cashCollectedToday(),
                    "the till figure is day-scoped, so tomorrow it is zero");
            assertEquals(new BigDecimal("0.00"), tomorrow.totalCollected(),
                    "and so are takings");
            assertEquals(new BigDecimal("199.00"), tomorrow.heldPending(),
                    "but the money IS still held, and correctly so - the recharge "
                            + "is still owed to the customer");

            // The old formula would have claimed Rs 199 of till money tomorrow,
            // on a day when the drawer saw nothing at all.
            assertNotEquals(tomorrow.cashCollectedToday(),
                    tomorrow.totalCollected().add(tomorrow.heldPending()),
                    "collected + held is NOT the till figure once a day boundary "
                            + "is crossed - the formula this replaces");
        }

        @Test
        @DisplayName("⚠ the drawer identity holds WITH a surcharge — the case that was wrong")
        void drawerIdentityWithSurcharge() {
            // THE TEST THAT WAS MISSING, and the defect it would have caught.
            //
            // This slice's notes, PR and plan all stated the identity as
            // `drawer = totalTakings + heldPending`. That balances only when
            // every surcharge is zero — which is true of the mobile-only fixture
            // in failureExplainsTheGap, and false on any day carrying a DTH sale.
            // A Rs 240 Dish TV recharge with the Rs 10 fee puts Rs 250 in the
            // drawer while totalTakings reports Rs 240, because takings are the
            // recharge FACE VALUE and the surcharge is its own income line by
            // decision (log §10.2, §15.11).
            //
            // Found by driving a real sale through a running server. Two correct
            // tests existed either side of it: one asserted the identity without
            // a surcharge, one asserted the surcharge totals without the
            // identity. Neither covered the gap.
            var completed = settle(ServiceType.TV, "DISHTV", "240.00", "key-ident-1");
            fulfilment.markCompleted(completed.recharges().get(0).getRechargeId(),
                    null, null, staffUserId);

            var held = settle(ServiceType.TV, "DISHTV", "200.00", "key-ident-2");
            fulfilment.markFailed(held.recharges().get(0).getRechargeId(),
                    "operator declined", staffUserId);

            var summary = history.daySummary(LocalDate.now());

            assertEquals(new BigDecimal("240.00"), summary.totalTakings(),
                    "face value of the completed recharge");
            assertEquals(new BigDecimal("10.00"), summary.totalSurcharge(),
                    "and its surcharge, reported apart");
            assertEquals(new BigDecimal("250.00"), summary.totalCollected(),
                    "what the customer actually handed over");
            assertEquals(new BigDecimal("210.00"), summary.heldPending(),
                    "the failed basket's 200 + 10 is still held");

            BigDecimal drawer = summary.tenderMix().get(SettlementMethod.CASH);
            assertEquals(new BigDecimal("460.00"), drawer, "both baskets were paid for");

            // ⚠ BOTH SUPERSEDED FORMS, asserted as wrong so neither comes back.
            //
            // takings + held omitted the surcharge and was Rs 10 short. Replacing
            // it with collected + held fixed that and was still wrong, because
            // heldPending is all-time and all-tender - see
            // heldPendingIsAllTime and upiDoesNotEnterTheTill. BOTH happen to
            // balance in this fixture, which is same-day and all-cash, and that
            // is exactly why neither was caught here.
            assertNotEquals(drawer, summary.totalTakings().add(summary.heldPending()),
                    "takings + held omits the surcharge");

            // The till figure is read from the ledger and cross-checked against
            // the tender rows. No addition for a client to get wrong.
            assertEquals(drawer, summary.cashCollectedToday(),
                    "the ledger's CASH_RECHARGE movement for the day equals the "
                            + "cash tendered - two independent sources agreeing");

            // The surcharge point this test exists for still stands: our charge is
            // real money that reached the drawer, and it is NOT in takings.
            assertEquals(new BigDecimal("250.00"), summary.totalCollected(),
                    "240 takings + 10 our charges is what the customer handed over");
            assertNotEquals(summary.totalTakings(), summary.totalCollected(),
                    "and it is not the same number as takings, which is the whole "
                            + "reason the first formula was wrong");
        }

        @Test
        @DisplayName("the surcharge is its own total, not folded into takings")
        void surchargeIsSeparate() {
            var settled = settle(ServiceType.TV, "DISHTV", "240.00", "key-sum-5");
            fulfilment.markCompleted(settled.recharges().get(0).getRechargeId(),
                    null, null, staffUserId);

            var summary = history.daySummary(LocalDate.now());

            assertEquals(new BigDecimal("240.00"), summary.totalTakings(),
                    "the recharge face value");
            assertEquals(new BigDecimal("10.00"), summary.totalSurcharge(),
                    "and the shop's own charge, reported apart - log §15.11's "
                            + "invariant, because it is 60% of DTH margin");
        }

        @Test
        @DisplayName("the tender mix splits by method")
        void tenderMix() {
            int customerId = insertCustomer("Tab", "Customer", "9000000011");
            settlement.settle(
                    List.of(new RequestedRecharge(ServiceType.MOBILE, "9000000002", "ARTL",
                            new BigDecimal("199.00"), null)),
                    List.of(new RequestedTender(SettlementMethod.CASH, new BigDecimal("100.00"),
                                    new BigDecimal("100.00"), null, null, null),
                            new RequestedTender(SettlementMethod.UPI, new BigDecimal("50.00"),
                                    null, "UPI/1", null, null),
                            new RequestedTender(SettlementMethod.CREDIT, new BigDecimal("49.00"),
                                    null, null, customerId, "Friday")),
                    staffUserId, "key-sum-6");

            var summary = history.daySummary(LocalDate.now());

            assertEquals(new BigDecimal("100.00"), summary.tenderMix().get(SettlementMethod.CASH));
            assertEquals(new BigDecimal("50.00"), summary.tenderMix().get(SettlementMethod.UPI));
            assertEquals(new BigDecimal("49.00"),
                    summary.tenderMix().get(SettlementMethod.CREDIT),
                    "CRDT on the column, CREDIT on the wire");
        }

        @Test
        @DisplayName("a withdrawn sale drops out of takings for free")
        void withdrawnSaleDropsOut() {
            // design-domain.md §5.2's reason for making this a status change
            // rather than a ledger-only correction: the takings query already
            // filters on COMPLETED, so moving the status is what fixes the
            // report. A ledger-only fix would leave the row reading COMPLETED and
            // the takings still inflated.
            //
            // ⚠ THIS USED TO DRIVE voidRecharge, which was removed on 2026-10-21.
            // The assertion it makes is about the TAKINGS QUERY and not about the
            // void path, so it is rewritten against COMPLETED -> FAILED rather
            // than deleted — that is now the only exit from COMPLETED, and it is
            // what records a sale that should never have been entered. The reason
            // carries the real explanation in place of the lost label.
            var settled = settle("ARTL", "199.00", "key-sum-7");
            int rechargeId = settled.recharges().get(0).getRechargeId();
            fulfilment.markCompleted(rechargeId, null, null, staffUserId);

            assertEquals(new BigDecimal("199.00"),
                    history.daySummary(LocalDate.now()).totalTakings());

            fulfilment.markFailed(rechargeId, "entered twice", staffUserId);

            assertEquals(new BigDecimal("0.00"),
                    history.daySummary(LocalDate.now()).totalTakings(),
                    "no query change needed");
        }
    }

    // ------------------------------------------------------------------
    // History
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("history")
    class History {

        @Test
        @DisplayName("⚠ it is genuinely empty before the first sale, and says so")
        void emptyOnDayOne() {
            // log §15.11: rc_recharge is empty until V15, so the view is blank on
            // day one and fills only as new sales are taken. "That is correct, not
            // a bug - but an empty screen with no explanation reads as broken."
            // An empty availableYears is how the screen tells the two apart.
            assertEquals(List.of(), history.dataAvailableFrom(),
                    "nothing recorded yet, which is a different sentence from "
                            + "'no results for this filter'");

            var page = history.search(null, null, null, null, null, null, null,
                    PageRequest.of(0, 20));
            assertEquals(0, page.page().getTotalElements());
        }

        @Test
        @DisplayName("a sale appears, and the year it resolved to comes back with it")
        void findsASale() {
            settle("BSNL", "147.00", "key-hist-1");

            var page = history.search(null, null, null, null, null, null, null,
                    PageRequest.of(0, 20));

            assertEquals(1, page.page().getTotalElements());
            // §3.6: the resolved year is echoed so the UI never computes
            // financial-year boundaries itself.
            assertTrue(page.financialYear().label().matches("^\\d{4}-\\d{2}$"),
                    page.financialYear().label());
            assertFalse(history.dataAvailableFrom().isEmpty());
        }

        @Test
        @DisplayName("the number filter is a PREFIX, not a contains")
        void numberIsAPrefix() {
            // §7.1(a): LIKE '%1234%' cannot use idx_rech_number and degrades to a
            // full scan. A contains mode is affordable at 52k rows but "must be a
            // deliberate, separate, slower mode, not the default."
            settle("BSNL", "147.00", "key-hist-2");

            assertEquals(1, history.search(null, "900", null, null, null, null, null,
                    PageRequest.of(0, 20)).page().getTotalElements(),
                    "a prefix matches");
            assertEquals(0, history.search(null, "000000", null, null, null, null, null,
                    PageRequest.of(0, 20)).page().getTotalElements(),
                    "a mid-string fragment does not, by design");
        }

        @Test
        @DisplayName("a year with no data returns empty rather than refusing")
        void anotherYearIsEmpty() {
            settle("BSNL", "147.00", "key-hist-3");

            var page = history.search("2019-20", null, null, null, null, null, null,
                    PageRequest.of(0, 20));
            assertEquals(0, page.page().getTotalElements());
            assertEquals("2019-20", page.financialYear().label());
        }

        @Test
        @DisplayName("a malformed year is a 400, not a silent default to this year")
        void malformedYear() {
            var ex = assertThrows(
                    com.mangle.retailshopapp.recharge.exception.RechargeBadRequestException.class,
                    () -> history.search("2026-28", null, null, null, null, null, null,
                            PageRequest.of(0, 20)));
            assertEquals(400, ex.status());
        }

        @Test
        @DisplayName("filtering by status finds the failed one")
        void statusFilter() {
            var settled = settle("RJIO", "299.00", "key-hist-4");
            fulfilment.markFailed(settled.recharges().get(0).getRechargeId(), "declined",
                    staffUserId);

            assertEquals(1, history.search(null, null, null, null, RechargeStatus.FAILED,
                    null, null, PageRequest.of(0, 20)).page().getTotalElements());
            assertEquals(0, history.search(null, null, null, null, RechargeStatus.COMPLETED,
                    null, null, PageRequest.of(0, 20)).page().getTotalElements());
        }
    }

    // ------------------------------------------------------------------
    // One visit, and the entry-step reads
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("reading one visit, and the entry step")
    class Reads {

        @Test
        @DisplayName("a visit comes back with its recharges and its tenders")
        void visit() {
            var settled = settle("BSNL", "147.00", "key-visit-1");

            var visit = history.visit(settled.paymentId());

            assertEquals(settled.paymentId(), visit.header().getTxnId());
            assertEquals(1, visit.recharges().size());
            assertEquals(1, visit.tenders().size());
            assertEquals(new BigDecimal("147.00"), visit.header().getTxnTotalAmt());
            // The tender and change live on the HEADER, not on the tender row -
            // one drawer, one hand-over, whatever the basket holds.
            assertEquals(new BigDecimal("150.00"), visit.header().getAmtTndred());
            assertEquals(new BigDecimal("3.00"), visit.header().getAmtReturned());
        }

        @Test
        @DisplayName("an unknown payment is a 404")
        void unknownVisit() {
            assertThrows(PaymentNotFound.class, () -> history.visit(999_999));
        }

        @Test
        @DisplayName("⚠ an unseen number is known:false, which is EVERY number on day one")
        void unseenNumber() {
            // It reads rc_recharge, empty until V15. §3.2 specifies 200 with
            // known:false rather than a 404 precisely so the commonest case does
            // not look like a failure.
            var hint = entry.hintFor(ServiceType.MOBILE, "9999999999");

            assertFalse(hint.known());
            assertEquals(null, hint.operatorCode());
        }

        @Test
        @DisplayName("a number seen before prefills its operator and the CURRENT default route")
        void seenNumber() {
            // 88.5% of mobile recharges go to a number seen before (log §14.10).
            settle("BSNL", "147.00", "key-hint-1");

            var hint = entry.hintFor(ServiceType.MOBILE, "9000000002");

            assertTrue(hint.known());
            assertEquals("BSNL", hint.operatorCode());
            assertEquals("A1TOPUP", hint.routeCode(),
                    "the operator's CURRENT default, not the route the last sale "
                            + "used - log §14.6 makes the route a completion-time decision");
            assertEquals(new BigDecimal("147.00"), hint.lastAmount());
        }

        @Test
        @DisplayName("the hint is per service type, so a TV number does not prefill a mobile")
        void hintIsPerServiceType() {
            settle("BSNL", "147.00", "key-hint-2");

            assertFalse(entry.hintFor(ServiceType.TV, "9000000002").known(),
                    "the same digits as a TV connection number would be a different thing");
        }

        @Test
        @DisplayName("a connection search under two characters returns empty, not an error")
        void shortSearch() {
            // A staff member mid-keystroke has not made a mistake.
            assertEquals(List.of(), entry.searchConnections("0", 20));
            assertEquals(List.of(), entry.searchConnections(null, 20));
        }
    }

    // ------------------------------------------------------------------

    private RechargeSettlementService.Settled settle(String operator, String amount, String key) {
        return settle(ServiceType.MOBILE, operator, amount, key);
    }

    private RechargeSettlementService.Settled settle(ServiceType type, String operator,
                                                     String amount, String key) {
        BigDecimal face = new BigDecimal(amount);
        BigDecimal surcharge = type == ServiceType.TV ? new BigDecimal("10.00") : BigDecimal.ZERO;
        BigDecimal total = face.add(surcharge);
        String number = type == ServiceType.TV ? "02700000001" : "9000000002";

        return settlement.settle(
                List.of(new RequestedRecharge(type, number, operator, face, null)),
                List.of(new RequestedTender(SettlementMethod.CASH, total,
                        total.add(new BigDecimal("3.00")), null, null, null)),
                staffUserId, key);
    }

    private int insertStaffUser() {
        em.createNativeQuery("INSERT INTO rc_user (USER_NAME, PASSWORD, ROLES, ACCOUNT_STATUS) "
                        + "VALUES (:n, 'x', 'ROLE_USER', 'ACTIVE')")
                .setParameter("n", "staff" + System.nanoTime())
                .executeUpdate();
        em.flush();
        return lastInsertId();
    }

    private int insertCustomer(String first, String last, String contact) {
        em.createNativeQuery("INSERT INTO rs_cust_dtls (FIRST_NAME, LAST_NAME, CONTACT_NUM) "
                        + "VALUES (:f, :l, :c)")
                .setParameter("f", first).setParameter("l", last).setParameter("c", contact)
                .executeUpdate();
        em.flush();
        return lastInsertId();
    }

    private int lastInsertId() {
        return ((Number) em.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult())
                .intValue();
    }
}
