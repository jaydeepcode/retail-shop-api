package com.mangle.retailshopapp.recharge.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.mangle.retailshopapp.accounting.LedgerPostingService;
import com.mangle.retailshopapp.accounting.PostedVoucher;
import com.mangle.retailshopapp.recharge.domain.FloatConsumption;
import com.mangle.retailshopapp.recharge.domain.RechargeStatus;
import com.mangle.retailshopapp.recharge.domain.ServiceType;
import com.mangle.retailshopapp.recharge.domain.SettlementMethod;
import com.mangle.retailshopapp.recharge.domain.SettlementPlan;
import com.mangle.retailshopapp.recharge.domain.SettlementPlan.RechargeLine;
import com.mangle.retailshopapp.recharge.domain.SettlementPlan.SettlementLine;
import com.mangle.retailshopapp.recharge.exception.FloatAccountNotDerivableException;
import com.mangle.retailshopapp.recharge.exception.NoCommissionRateException;
import com.mangle.retailshopapp.recharge.model.RcOperator;
import com.mangle.retailshopapp.recharge.model.RcRecharge;
import com.mangle.retailshopapp.recharge.model.RefRechargeRoute;
import com.mangle.retailshopapp.recharge.repo.RcOperatorRepository;
import com.mangle.retailshopapp.recharge.repo.RefRechargeRouteRepository;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two-stage recharge posting against real MySQL, with V23's triggers live.
 *
 * <p>next-session-s3.md names these as the assertions that matter: "a settled
 * basket must leave {@code CASH_RECHARGE} and {@code RCHG_PENDING} equal and
 * opposite; a completed recharge must leave {@code SUM(DR) = SUM(CR)} on its
 * income voucher and {@code INC_RCHG_COMM = A − costConsumed} exactly."
 *
 * <p>The container is not optional. V23's trigger 1 sums a voucher's legs at the
 * moment it is sealed and refuses an unbalanced one, so the whole point of the
 * leg arithmetic is a database behaviour H2 does not have. A test on H2 would
 * assert that the Java compiles.
 *
 * <p>Flyway is capped at 28 — S3's own head — so the surcharge column, the
 * measured presets and the repointed default routes are all present.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({RechargeLedgerPostings.class, LedgerPostingService.class})
@DisplayName("The two-stage recharge posting, against MySQL")
class RechargeLedgerPostingsTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.43")
            .withDatabaseName("recharge")
            // MySQL refuses CREATE TRIGGER from a non-SUPER account while binary
            // logging is on (ERROR 1419). See LedgerMigrationTest.
            .withCommand("--log-bin-trust-function-creators=1")
            .withInitScript("db/legacy-baseline.sql");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        // The migrations own the schema; Hibernate must not create its own
        // FK-less, trigger-less copy (implementation-plan.md §0.1).
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("spring.flyway.baseline-version", () -> "8");
        registry.add("spring.flyway.target", () -> "28");
        registry.add("spring.flyway.out-of-order", () -> "false");
    }

    /**
     * ⚠ DERIVED FROM TODAY, NOT HARDCODED, and the reason is a failure this test
     * actually had.
     *
     * <p>It was pinned to {@code 2026-10-12 18:04:11} and passed — until the next
     * day, when every completion test broke with "Bsnl has no commission rate on
     * file". V25 seeds {@code EFFECTIVE_FROM = CURDATE()}, which is the
     * migration's own run date and deliberate (log §12: the go-live date must not
     * be baked in at build time). The container runs the migration today, so a
     * recharge dated yesterday falls BEFORE the rate's effective date and §4.2's
     * lookup correctly returns nothing.
     *
     * <p>The lookup is right and the fixture was wrong: §4.2 is explicit that the
     * rate must be found as of {@code :rechargeDate} rather than
     * {@code CURDATE()}, because "a recharge entered a day late must use the rate
     * that applied on the day it happened".
     *
     * <p>Worth carrying into the cutover: a recharge dated before the rate rows
     * exist has no rate, so V15's backfilled history could never post an income
     * voucher. That is fine today — the ledger is not authoritative for recharge
     * until the legacy writer retires (risk R1) and nothing posts those rows —
     * but it is a real constraint on any future attempt to post history.
     */
    private static final LocalDateTime WHEN = LocalDate.now().atTime(18, 4, 11);

    @Autowired
    private RechargeLedgerPostings postings;

    @Autowired
    private RcOperatorRepository operators;

    @Autowired
    private RefRechargeRouteRepository routes;

    @Autowired
    private EntityManager em;

    private RefRechargeRoute a1topup;
    private RefRechargeRoute direct;
    private int staffUserId;

    @BeforeEach
    void loadConfig() {
        a1topup = routes.findById("A1TOPUP").orElseThrow();
        direct = routes.findById("DIRECT").orElseThrow();
        // acc_voucher.CRE_BY foreign-keys rc_user, so a voucher needs a real
        // staff row. Worth knowing rather than working around: it means every
        // automatic posting is attributable, which is the same control §3.5
        // relies on for credit.
        //
        // Guarded because mandatoryPropagation() runs with Propagation.NEVER, so
        // there is no transaction to insert in — and that test does not need a
        // user: it asserts the post is refused before any leg is built.
        staffUserId = TransactionSynchronizationManager.isActualTransactionActive()
                ? insertStaffUser()
                : 1;
    }

    // ------------------------------------------------------------------
    // Stage one — the cash leg
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("stage one: payment settled")
    class Settlement {

        @Test
        @DisplayName("a cash basket leaves CASH_RECHARGE and RCHG_PENDING equal and opposite")
        void cashBasket() {
            SettlementPlan plan = SettlementPlan.of(
                    List.of(mobileLine("199.00", "0.00")),
                    List.of(cashLine("199.00", "200.00")));

            PostedVoucher posted = postings.postSettlement(73482, plan, staffUserId, WHEN);

            assertFalse(posted.replayed());
            assertEquals(2, posted.legCount());
            assertEquals(new BigDecimal("199.00"), drOn(posted, "CASH_RECHARGE"));
            assertEquals(new BigDecimal("199.00"), crOn(posted, "RCHG_PENDING"));
            assertBalances(posted);
        }

        @Test
        @DisplayName("the surcharge is inside RCHG_PENDING, because the customer paid it")
        void surchargeIsHeldPending() {
            // The brief's worked example: a Rs 240 Dish TV recharge with the Rs 10
            // fee, customer paying Rs 250 cash.
            SettlementPlan plan = SettlementPlan.of(
                    List.of(tvLine("240.00", "10.00")),
                    List.of(cashLine("250.00", "250.00")));

            PostedVoucher posted = postings.postSettlement(73483, plan, staffUserId, WHEN);

            assertEquals(new BigDecimal("250.00"), drOn(posted, "CASH_RECHARGE"));
            assertEquals(new BigDecimal("250.00"), crOn(posted, "RCHG_PENDING"),
                    "all 250 is held: the completion voucher is what splits it three ways");
            assertBalances(posted);
        }

        @Test
        @DisplayName("three tenders reach three different accounts")
        void splitBasket() {
            // §3.3's own example, and the reason the debit side is per line: the
            // day's reconciliation needs cash in the drawer, UPI awaiting
            // next-morning settlement, and a receivable on a named customer kept
            // apart.
            int customerId = insertCustomer("Test", "Customer", "9000000003");

            SettlementPlan plan = SettlementPlan.of(
                    List.of(mobileLine("219.00", "0.00"), tvLine("200.00", "10.00")),
                    List.of(cashLine("300.00", "500.00"), upiLine("100.00"),
                            creditLine("29.00", customerId)));

            PostedVoucher posted = postings.postSettlement(73484, plan, staffUserId, WHEN);

            assertEquals(4, posted.legCount());
            assertEquals(new BigDecimal("300.00"), drOn(posted, "CASH_RECHARGE"));
            assertEquals(new BigDecimal("100.00"), drOn(posted, "UPI_CLEARING"));
            assertEquals(new BigDecimal("29.00"), drOn(posted, "AR_RECHARGE"));
            assertEquals(new BigDecimal("429.00"), crOn(posted, "RCHG_PENDING"));
            assertBalances(posted);

            assertEquals(customerId, partyOn(posted, "AR_RECHARGE"),
                    "AR_RECHARGE has REQUIRES_PARTY = TRUE: a receivable with no "
                            + "party cannot appear on anyone's statement");
        }

        @Test
        @DisplayName("change never reaches the ledger")
        void changeIsNotAVoucher() {
            // design-ledger.md §11's E5: cash change is not a voucher. The
            // customer handed over 500 and took 301 back, so only 199 ever
            // belonged to the shop.
            SettlementPlan plan = SettlementPlan.of(
                    List.of(mobileLine("199.00", "0.00")),
                    List.of(cashLine("199.00", "500.00")));

            PostedVoucher posted = postings.postSettlement(73485, plan, staffUserId, WHEN);

            assertEquals(new BigDecimal("199.00"), drOn(posted, "CASH_RECHARGE"),
                    "the tender was 500 and the change 301; neither is a ledger amount");
            assertEquals(new BigDecimal("301.00"), plan.changeDue());
            assertBalances(posted);
        }

        @Test
        @DisplayName("posting the same payment twice writes nothing the second time")
        void idempotent() {
            SettlementPlan plan = SettlementPlan.of(
                    List.of(mobileLine("147.00", "0.00")),
                    List.of(cashLine("147.00", "147.00")));

            PostedVoucher first = postings.postSettlement(73486, plan, staffUserId, WHEN);
            PostedVoucher second = postings.postSettlement(73486, plan, staffUserId, WHEN);

            assertFalse(first.replayed());
            assertTrue(second.replayed(), "a replay returns the existing voucher");
            assertEquals(first.voucherId(), second.voucherId());
            assertEquals(1L, count(
                    "select count(v) from AccVoucher v where v.idempotencyKey = :k",
                    "k", RechargeLedgerPostings.settlementKey(73486)));
        }

        @Test
        @DisplayName("the idempotency key follows §5.4's format, not log §7.2's shorthand")
        void keyFormat() {
            assertEquals("RECHARGE_SALE:rc_txn_header#73482:SETTLED",
                    RechargeLedgerPostings.settlementKey(73482));
            assertEquals("RECHARGE_SALE:rc_recharge#99051:COMPLETED",
                    RechargeLedgerPostings.completionKey(99051L));
        }
    }

    // ------------------------------------------------------------------
    // Stage two — the income leg
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("stage two: recharge completed")
    class Completion {

        @Test
        @DisplayName("the brief's Dish TV example posts leg for leg")
        void dishTvExample() {
            // SETTLED        Dr CASH_RECHARGE       250.00
            //                Cr RCHG_PENDING        250.00
            // COMPLETED      Dr RCHG_PENDING        250.00
            //                Cr FLOAT_A1TOPUP       231.36   240 x (1 - 0.036)
            //                Cr INC_RCHG_COMM         8.64   240 - 231.36
            //                Cr INC_RCHG_SURCHARGE   10.00   the shop's own charge
            RcRecharge recharge = recharge(ServiceType.TV, "DISHTV", "A1TOPUP",
                    "240.00", "10.00");
            RcOperator dishtv = operators.findById("DISHTV").orElseThrow();

            PostedVoucher posted = postings.postCompletion(recharge, dishtv, a1topup, staffUserId, WHEN);

            assertEquals(4, posted.legCount());
            assertEquals(new BigDecimal("250.00"), drOn(posted, "RCHG_PENDING"));
            assertEquals(new BigDecimal("231.36"), crOn(posted, "FLOAT_A1TOPUP"));
            assertEquals(new BigDecimal("8.64"), crOn(posted, "INC_RCHG_COMM"));
            assertEquals(new BigDecimal("10.00"), crOn(posted, "INC_RCHG_SURCHARGE"));
            assertBalances(posted);
        }

        @Test
        @DisplayName("INC_RCHG_COMM is exactly A - costConsumed, by subtraction")
        void commissionBySubtraction() {
            // The assertion the brief singles out. BSNL at 3.0% on Rs 147 — an
            // amount where A x r and A - round(A x (1-r)) do NOT agree.
            RcRecharge recharge = recharge(ServiceType.MOBILE, "BSNL", "A1TOPUP",
                    "147.00", "0.00");
            RcOperator bsnl = operators.findById("BSNL").orElseThrow();

            FloatConsumption expected = FloatConsumption.atParity(
                    new BigDecimal("147.00"), new BigDecimal("0.03"));

            PostedVoucher posted = postings.postCompletion(recharge, bsnl, a1topup, staffUserId, WHEN);

            assertEquals(expected.faceConsumed(), crOn(posted, "FLOAT_A1TOPUP"));
            assertEquals(expected.commission(), crOn(posted, "INC_RCHG_COMM"));
            assertEquals(new BigDecimal("147.00"),
                    crOn(posted, "FLOAT_A1TOPUP").add(crOn(posted, "INC_RCHG_COMM")),
                    "A = costConsumed + commission, exactly, with no plug figure");
            assertBalances(posted);
        }

        @Test
        @DisplayName("no surcharge means no surcharge leg at all, not a zero one")
        void noSurchargeLeg() {
            // A zero leg would be refused by ck_line_nonzero anyway, and
            // LedgerPostingService checks it first to give a sentence. Worth
            // asserting because the mobile default IS zero, so this is the
            // commonest shape there is.
            RcRecharge recharge = recharge(ServiceType.MOBILE, "RJIO", "DIRECT",
                    "299.00", "0.00");
            RcOperator jio = operators.findById("RJIO").orElseThrow();

            PostedVoucher posted = postings.postCompletion(recharge, jio, direct, staffUserId, WHEN);

            assertEquals(3, posted.legCount());
            assertEquals(new BigDecimal("0.00"), crOn(posted, "INC_RCHG_SURCHARGE"));
            assertBalances(posted);
        }

        @Test
        @DisplayName("the float wallet is derived from the route, not from a mapping table")
        void floatIsDerived() {
            // log §15.1: route = 'A1TOPUP' -> FLOAT_A1TOPUP;
            //            route = 'DIRECT'  -> CONCAT('FLOAT_', OPERATOR_CODE).
            // The same operator on two routes consumes two different wallets, and
            // that is the whole reason there is no mapping table.
            RcOperator jio = operators.findById("RJIO").orElseThrow();

            PostedVoucher viaDirect = postings.postCompletion(
                    recharge(ServiceType.MOBILE, "RJIO", "DIRECT", "239.00", "0.00"),
                    jio, direct, staffUserId, WHEN);
            PostedVoucher viaA1 = postings.postCompletion(
                    recharge(ServiceType.MOBILE, "RJIO", "A1TOPUP", "239.00", "0.00"),
                    jio, a1topup, staffUserId, WHEN);

            assertTrue(crOn(viaDirect, "FLOAT_RJIO").signum() > 0,
                    "DIRECT consumes the operator's own wallet");
            assertTrue(crOn(viaA1, "FLOAT_A1TOPUP").signum() > 0,
                    "A1TOPUP consumes the shared wallet");

            // And the rates genuinely differ, so this is not a cosmetic split:
            // Jio is 2.5% direct and 0.8% through A1Topup.
            assertTrue(crOn(viaDirect, "INC_RCHG_COMM")
                            .compareTo(crOn(viaA1, "INC_RCHG_COMM")) > 0,
                    "the direct route earns more, which is why the route matters");
        }

        @Test
        @DisplayName("a missing rate refuses the income post and names the operator")
        void missingRateRefuses() {
            // log §8.2. RNRL is seeded INACTIVE with no rate at all, which makes
            // it the one operator that can reach this state — and the refusal
            // must carry the display name, because the counter shows
            // "<Operator> has no commission rate on file - add one to complete".
            RcRecharge recharge = recharge(ServiceType.MOBILE, "RNRL", "A1TOPUP",
                    "199.00", "0.00");
            RcOperator rnrl = operators.findById("RNRL").orElseThrow();

            NoCommissionRateException ex = assertThrows(NoCommissionRateException.class, () ->
                    postings.postCompletion(recharge, rnrl, a1topup, staffUserId, WHEN));

            assertTrue(ex.getMessage().contains("no commission rate on file"), ex.getMessage());
            assertTrue(ex.getMessage().contains(rnrl.getDisplayName()), ex.getMessage());
            assertEquals(422, ex.status());
            assertEquals("RNRL", ex.details().get("operatorCode"));
        }

        @Test
        @DisplayName("a missing rate does NOT default to zero")
        void rateIsNotZeroed() {
            // The specific reversal of design-ledger.md:800-804. r = 0 is not
            // neutral: it also sets the float decrement, so it would record the
            // portal debiting Rs 199 on a Rs 199 sale when it really debited
            // Rs 193.23 — understating commission AND drifting SUM(FACE_DELTA)
            // from the portal by the same amount.
            RcRecharge recharge = recharge(ServiceType.MOBILE, "RNRL", "A1TOPUP",
                    "199.00", "0.00");
            RcOperator rnrl = operators.findById("RNRL").orElseThrow();

            assertThrows(NoCommissionRateException.class, () ->
                    postings.postCompletion(recharge, rnrl, a1topup, staffUserId, WHEN));

            assertEquals(0L, count("select count(v) from AccVoucher v where v.idempotencyKey = :k",
                            "k", RechargeLedgerPostings.completionKey(recharge.getRechargeId())),
                    "nothing was posted at r = 0");
        }

        @Test
        @DisplayName("completing twice posts once")
        void idempotent() {
            RcRecharge recharge = recharge(ServiceType.MOBILE, "ARTL", "A1TOPUP",
                    "199.00", "0.00");
            RcOperator artl = operators.findById("ARTL").orElseThrow();

            PostedVoucher first = postings.postCompletion(recharge, artl, a1topup, staffUserId, WHEN);
            PostedVoucher second = postings.postCompletion(recharge, artl, a1topup, staffUserId, WHEN);

            assertFalse(first.replayed());
            assertTrue(second.replayed());
            assertEquals(first.voucherId(), second.voucherId());
        }

        @Test
        @DisplayName("a float account the derivation invents is refused with a usable message")
        void derivedFloatAccountMustExist() {
            // BSNL on DIRECT derives FLOAT_BSNL, which does not exist — the shop
            // holds four wallets. This is exactly the state V10's
            // DEFAULT_ROUTE_CODE = 'DIRECT' would have produced for 37% of
            // recharges before V28 repointed them, so the message has to name the
            // real fault rather than just the missing account.
            RcRecharge recharge = recharge(ServiceType.MOBILE, "BSNL", "DIRECT",
                    "147.00", "0.00");
            RcOperator bsnl = operators.findById("BSNL").orElseThrow();

            // ⚠ A 422, not an IllegalStateException. This test asserted the latter
            // until review finding 13: an IllegalStateException falls through to
            // the catch-all handler, so the counter received
            // "An unexpected error occurred." and the carefully-written message
            // existed only in the server log — on the one failure a staff member
            // can act on, by choosing a different route.
            FloatAccountNotDerivableException ex = assertThrows(
                    FloatAccountNotDerivableException.class, () ->
                            postings.postCompletion(recharge, bsnl, direct, staffUserId, WHEN));

            assertEquals(422, ex.status());
            assertTrue(ex.getMessage().contains("BSNL"), ex.getMessage());
            assertTrue(ex.getMessage().contains("A1 Topup"), "it says what to do instead");
            assertEquals("FLOAT_BSNL", ex.details().get("derivedAccount"),
                    "and names the wallet the derivation invented");

            // ⚠ AND IT MUST NOT BE THE RATE ERROR. BSNL on DIRECT has neither a
            // wallet nor a rate, so whichever check runs first decides the
            // message — and "Bsnl has no commission rate on file, add one to
            // complete" would send someone to add a rate for a route BSNL has no
            // connection on (§2.4:86). This assertion is what pins the order.
            assertFalse(ex.getMessage().contains("commission rate"), ex.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // The boundary itself
    // ------------------------------------------------------------------

    @Test
    @DisplayName("posting outside a transaction throws, which is what MANDATORY is for")
    @Transactional(propagation = Propagation.NEVER)
    void mandatoryPropagation() {
        // §5.1 calls this "the single most valuable line in this design": with
        // REQUIRED a caller that forgets @Transactional would silently get its own
        // transaction, the ledger write would commit separately from the
        // operational write, and FRD §8.5's single-source-of-truth guarantee would
        // quietly evaporate with nothing failing to say so.
        SettlementPlan plan = SettlementPlan.of(
                List.of(mobileLine("199.00", "0.00")),
                List.of(cashLine("199.00", "199.00")));

        assertThrows(IllegalTransactionStateException.class, () ->
                postings.postSettlement(99999, plan, 1, WHEN));
    }

    // ------------------------------------------------------------------
    // Fixtures and assertions
    // ------------------------------------------------------------------

    private static RechargeLine mobileLine(String amount, String surcharge) {
        return new RechargeLine(ServiceType.MOBILE, "9000000002", "BSNL", "A1TOPUP",
                new BigDecimal(amount), new BigDecimal(surcharge));
    }

    private static RechargeLine tvLine(String amount, String surcharge) {
        return new RechargeLine(ServiceType.TV, "02700000001", "DISHTV", "A1TOPUP",
                new BigDecimal(amount), new BigDecimal(surcharge));
    }

    private static SettlementLine cashLine(String amount, String tendered) {
        return new SettlementLine(SettlementMethod.CASH, new BigDecimal(amount),
                new BigDecimal(tendered), null, null, null);
    }

    private static SettlementLine upiLine(String amount) {
        return new SettlementLine(SettlementMethod.UPI, new BigDecimal(amount), null,
                "UPI/447182003921", null, null);
    }

    private static SettlementLine creditLine(String amount, Integer customerId) {
        return new SettlementLine(SettlementMethod.CREDIT, new BigDecimal(amount), null, null,
                customerId, "will pay Friday");
    }

    /**
     * An UNSAVED recharge carrying an id, which is all the posting needs.
     *
     * <p>Deliberately not persisted: {@code rc_recharge} foreign-keys
     * {@code rc_txn_details}, so a saved row would need a basket and an item line
     * per test, and this class is testing the VOUCHER rather than the operational
     * write. The settle path that creates both is covered by the settlement
     * service's own test.
     */
    private static RcRecharge recharge(ServiceType kind, String operator, String route,
                                       String amount, String surcharge) {
        RcRecharge r = new RcRecharge();
        r.setRechargeId(NEXT_ID++);
        r.setRechargeKind(kind);
        r.setOperatorCode(operator);
        r.setRouteCode(route);
        r.setTargetNumber("9000000002");
        r.setAmount(new BigDecimal(amount));
        r.setSurcharge(new BigDecimal(surcharge));
        r.setRechargeDttm(WHEN);
        r.setStatusCode(RechargeStatus.AWAITING_RECHARGE);
        r.setTxnId(73482);
        r.setSeqNo(1);
        r.setCreDttm(WHEN);
        return r;
    }

    private static long NEXT_ID = 99_000L;

    private int insertStaffUser() {
        em.createNativeQuery("INSERT INTO rc_user (USER_NAME, PASSWORD, ROLES, ACCOUNT_STATUS) "
                        + "VALUES (:n, 'x', 'ROLE_USER', 'ACTIVE')")
                .setParameter("n", "staff" + System.nanoTime())
                .executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult())
                .intValue();
    }

    private int insertCustomer(String first, String last, String contact) {
        em.createNativeQuery("INSERT INTO rs_cust_dtls (FIRST_NAME, LAST_NAME, CONTACT_NUM) "
                        + "VALUES (:f, :l, :c)")
                .setParameter("f", first).setParameter("l", last).setParameter("c", contact)
                .executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult())
                .intValue();
    }

    private int partyOn(PostedVoucher posted, String accountCode) {
        Object party = em.createQuery("select l.partyCustId from AccVoucherLine l "
                        + "where l.voucherId = :v and l.accountCode = :a")
                .setParameter("v", posted.voucherId())
                .setParameter("a", accountCode)
                .getSingleResult();
        return ((Number) party).intValue();
    }

    private BigDecimal drOn(PostedVoucher posted, String accountCode) {
        return sideOn(posted, accountCode, "drAmount");
    }

    private BigDecimal crOn(PostedVoucher posted, String accountCode) {
        return sideOn(posted, accountCode, "crAmount");
    }

    private BigDecimal sideOn(PostedVoucher posted, String accountCode, String field) {
        Object sum = em.createQuery("select coalesce(sum(l." + field + "), 0) "
                        + "from AccVoucherLine l "
                        + "where l.voucherId = :v and l.accountCode = :a")
                .setParameter("v", posted.voucherId())
                .setParameter("a", accountCode)
                .getSingleResult();
        return ((BigDecimal) sum).setScale(2);
    }

    /**
     * {@code SUM(DR) = SUM(CR)} on the voucher, read back from the database.
     *
     * <p>V23's trigger 1 has already refused an unbalanced voucher by the time
     * this runs, so a failure here would mean the trigger did not fire — which is
     * the failure mode {@code LedgerMigrationTest} exists to catch and which no
     * structural assertion can see.
     */
    private void assertBalances(PostedVoucher posted) {
        Object[] totals = (Object[]) em.createQuery(
                        "select coalesce(sum(l.drAmount), 0), coalesce(sum(l.crAmount), 0) "
                                + "from AccVoucherLine l where l.voucherId = :v")
                .setParameter("v", posted.voucherId())
                .getSingleResult();

        BigDecimal dr = ((BigDecimal) totals[0]).setScale(2);
        BigDecimal cr = ((BigDecimal) totals[1]).setScale(2);
        assertEquals(0, dr.compareTo(cr), "voucher " + posted.voucherId()
                + " does not balance: DR " + dr + " vs CR " + cr);
        assertTrue(dr.signum() > 0, "a voucher with no amount is not a voucher");
    }

    private long count(String jpql, String param, Object value) {
        return (Long) em.createQuery(jpql).setParameter(param, value).getSingleResult();
    }
}
