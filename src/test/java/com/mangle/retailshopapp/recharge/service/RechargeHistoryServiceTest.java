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
import com.mangle.retailshopapp.recharge.domain.SettlementMethod;
import com.mangle.retailshopapp.recharge.exception.PaymentNotFound;
import com.mangle.retailshopapp.recharge.service.RechargeSettlementService.RequestedRecharge;
import com.mangle.retailshopapp.recharge.service.RechargeSettlementService.RequestedTender;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        @DisplayName("a voided sale drops out of takings for free")
        void voidDropsOut() {
            // design-domain.md §5.2's reason for making this a status change
            // rather than a ledger-only correction: the takings query already
            // filters on COMPLETED, so moving the status is what fixes the
            // report. A ledger-only fix would leave the row reading COMPLETED and
            // the takings still inflated.
            var settled = settle("ARTL", "199.00", "key-sum-7");
            long rechargeId = settled.recharges().get(0).getRechargeId();
            fulfilment.markCompleted(rechargeId, null, null, staffUserId);

            assertEquals(new BigDecimal("199.00"),
                    history.daySummary(LocalDate.now()).totalTakings());

            fulfilment.voidRecharge(rechargeId, "entered twice", staffUserId);

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
