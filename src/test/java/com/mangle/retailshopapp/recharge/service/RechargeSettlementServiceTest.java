package com.mangle.retailshopapp.recharge.service;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
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
import com.mangle.retailshopapp.recharge.exception.LinesDoNotSumException;
import com.mangle.retailshopapp.recharge.model.RcRecharge;
import com.mangle.retailshopapp.recharge.service.RechargeSettlementService.RequestedRecharge;
import com.mangle.retailshopapp.recharge.service.RechargeSettlementService.RequestedTender;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Settle a basket, then complete it — the slice end to end, against real MySQL.
 *
 * <p>next-session-s3.md's rule: "Run it before claiming it works. S1's two real
 * defects and S2's five were all invisible on the page and obvious on execution."
 * This is the test that exercises the whole path rather than the voucher
 * arithmetic in isolation: one transaction writing a header, item lines,
 * recharges, tenders, a credit row and the cash voucher, then a second
 * transaction completing a recharge and posting the income voucher.
 *
 * <p>⚠ IT POSTS TO A CONTAINER, NEVER TO THE DEVELOPER RESTORE. The brief is
 * explicit: "the ledger stays empty until the opening journal … Containers are
 * where vouchers get posted."
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({RechargeSettlementService.class, RechargeFulfilmentService.class,
         RechargeLedgerPostings.class, LedgerPostingService.class,
         OperatorConfigService.class, CustomerDirectoryService.class,
         CreditLedgerService.class, SettingService.class, ClockConfig.class})
@DisplayName("Settle and complete, end to end")
class RechargeSettlementServiceTest {

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

    @Test
    @DisplayName("a cash sale writes one header, one item line, one recharge, one tender")
    void cashSale() {
        var settled = settlement.settle(
                List.of(mobile("BSNL", "147.00", null)),
                List.of(cash("147.00", "150.00")),
                staffUserId, "key-cash-1");

        assertEquals(new BigDecimal("147.00"), settled.plan().totalDue());
        assertEquals(1, settled.recharges().size());
        assertNull(settled.creditPosition(), "no credit line, no position to report");

        RcRecharge recharge = settled.recharges().get(0);
        assertEquals(RechargeStatus.AWAITING_RECHARGE, recharge.getStatusCode());
        assertEquals("A1TOPUP", recharge.getRouteCode(),
                "BSNL has no direct connection, so V28's repointed default applies");
        assertEquals(new BigDecimal("0.00"), recharge.getSurcharge(),
                "mobile defaults to no surcharge");
        assertEquals(staffUserId, recharge.getCreBy());

        assertEquals(1L, count("SELECT COUNT(*) FROM rc_txn_header WHERE TXN_ID = "
                + settled.paymentId()));
        assertEquals(1L, count("SELECT COUNT(*) FROM rc_txn_details WHERE TXN_ID = "
                + settled.paymentId()));
        assertEquals(1L, count("SELECT COUNT(*) FROM rc_txn_payment WHERE TXN_ID = "
                + settled.paymentId()));

        // The tender and change are on the HEADER, both non-negative — the
        // V13/V14 semantics rather than the legacy app's negative amt_returned.
        assertEquals("150.00", scalar("SELECT amt_tndred FROM rc_txn_header WHERE TXN_ID = "
                + settled.paymentId()));
        assertEquals("3.00", scalar("SELECT amt_returned FROM rc_txn_header WHERE TXN_ID = "
                + settled.paymentId()));
    }

    @Test
    @DisplayName("the TV surcharge defaults to Rs 10 and reaches the recharge row")
    void tvSurchargeDefaults() {
        var settled = settlement.settle(
                List.of(tv("DISHTV", "240.00", null)),
                List.of(cash("250.00", "250.00")),
                staffUserId, "key-tv-1");

        assertEquals(new BigDecimal("10.00"), settled.recharges().get(0).getSurcharge(),
                "DTH defaults on, from rc_setting['surcharge.default.tv']");
        assertEquals(new BigDecimal("250.00"), settled.plan().totalDue(),
                "the customer pays 240 + 10");
        assertEquals(new BigDecimal("240.00"), settled.recharges().get(0).getAmount(),
                "and the recharge itself is still 240 — never folded together");
    }

    @Test
    @DisplayName("an explicit zero surcharge on a DTH line sticks")
    void surchargeIsEditable() {
        // log §15.11: "the staff can change or clear it. The owner was explicit."
        // So an explicit zero must not be mistaken for an absent field and
        // re-defaulted to 10.
        var settled = settlement.settle(
                List.of(tv("DISHTV", "240.00", "0.00")),
                List.of(cash("240.00", "240.00")),
                staffUserId, "key-tv-2");

        assertEquals(new BigDecimal("0.00"), settled.recharges().get(0).getSurcharge());
        assertEquals(new BigDecimal("240.00"), settled.plan().totalDue());
    }

    @Test
    @DisplayName("a surcharge on a MOBILE line is allowed, because the field is editable")
    void mobileSurchargeAllowed() {
        // §15.11 narrows the DEFAULT to DTH but keeps the field editable, so the
        // 198 historical mobile cases stay reproducible. ck_rech_surcharge is
        // >= 0 rather than keyed to RECHARGE_KIND for exactly this reason.
        var settled = settlement.settle(
                List.of(mobile("ARTL", "199.00", "10.00")),
                List.of(cash("209.00", "210.00")),
                staffUserId, "key-mob-sur");

        assertEquals(new BigDecimal("10.00"), settled.recharges().get(0).getSurcharge());
    }

    @Test
    @DisplayName("a two-recharge basket shares one payment and one voucher")
    void twoRechargeBasket() {
        // 57 of 978 FY baskets hold two. Each recharge is its own row with its
        // own surcharge and its own eventual completion, but one payment settles
        // both — which is what makes the surcharge a per-recharge column rather
        // than a basket-level line.
        var settled = settlement.settle(
                List.of(mobile("RJIO", "299.00", null), tv("DISHTV", "240.00", null)),
                List.of(cash("549.00", "550.00")),
                staffUserId, "key-two-1");

        assertEquals(2, settled.recharges().size());
        assertEquals(new BigDecimal("549.00"), settled.plan().totalDue());
        assertEquals(new BigDecimal("10.00"), settled.plan().totalSurcharge(),
                "only the TV line carries one");

        assertEquals(settled.recharges().get(0).getTxnId(),
                settled.recharges().get(1).getTxnId(), "one payment");
        assertTrue(settled.recharges().get(0).getSeqNo()
                != settled.recharges().get(1).getSeqNo(), "two item lines");

        // The cash voucher is one voucher for the basket, not one per recharge.
        assertEquals(1L, count("SELECT COUNT(*) FROM acc_voucher WHERE IDEMPOTENCY_KEY = '"
                + RechargeLedgerPostings.settlementKey(settled.paymentId()) + "'"));
    }

    @Test
    @DisplayName("a credit sale writes the tab, links it to the tender, and reports the balance")
    void creditSale() {
        int customerId = insertCustomer("Credit", "Customer", "9000000009");

        var settled = settlement.settle(
                List.of(mobile("ARTL", "199.00", null)),
                List.of(cash("150.00", "150.00"), credit("49.00", customerId)),
                staffUserId, "key-credit-1");

        // The tab: stored NEGATIVE, as all 2,844 historical DBT rows are.
        assertEquals("-49.00", scalar("SELECT req_amt FROM rc_credit_req WHERE CUST_ID = "
                + customerId));
        assertEquals("DBT", scalar("SELECT req_type FROM rc_credit_req WHERE CUST_ID = "
                + customerId));
        assertEquals(String.valueOf(staffUserId),
                scalar("SELECT CRE_BY FROM rc_credit_req WHERE CUST_ID = " + customerId),
                "who extended it IS the authorisation control (§3.5)");

        // ck_txnpay_credit requires the CRDT tender to carry its credit row.
        assertNotNull(scalar("SELECT CRE_REQ_ID FROM rc_txn_payment WHERE TXN_ID = "
                + settled.paymentId() + " AND LINE_TYPE = 'CRDT'"));

        // And the header names the customer, which is §5.3's cross-table rule.
        assertEquals(String.valueOf(customerId),
                scalar("SELECT CUST_ID FROM rc_txn_header WHERE TXN_ID = " + settled.paymentId()));

        // The position is read inside the transaction, so it is the committed one.
        assertNotNull(settled.creditPosition());
        assertTrue(settled.creditPosition().owes());
        assertEquals(new BigDecimal("49.00"), settled.creditPosition().owed(),
                "shown positive, whatever SUM(req_amt)'s sign is");
    }

    @Test
    @DisplayName("the same idempotency key twice is refused by the database")
    void idempotencyKeyIsEnforced() {
        settlement.settle(List.of(mobile("BSNL", "147.00", null)),
                List.of(cash("147.00", "147.00")), staffUserId, "key-dup");

        // §7.1: the duplicate aborts the whole transaction before any money is
        // written, which is the correct outcome. The controller recovers the
        // original result in a FRESH read-only transaction, because the stored
        // row is not visible from inside the aborted one.
        assertThrows(DataIntegrityViolationException.class, () ->
                settlement.settle(List.of(mobile("BSNL", "147.00", null)),
                        List.of(cash("147.00", "147.00")), staffUserId, "key-dup"));
    }

    @Test
    @DisplayName("a basket whose lines do not sum writes nothing at all")
    void refusalWritesNothing() {
        long headersBefore = count("SELECT COUNT(*) FROM rc_txn_header");
        long keysBefore = count("SELECT COUNT(*) FROM rc_idempotency");

        assertThrows(LinesDoNotSumException.class, () ->
                settlement.settle(List.of(mobile("BSNL", "147.00", null)),
                        List.of(cash("100.00", "100.00")), staffUserId, "key-short"));

        assertEquals(headersBefore, count("SELECT COUNT(*) FROM rc_txn_header"),
                "step 1 runs before any I/O, so a bad basket costs no writes");
        assertEquals(keysBefore, count("SELECT COUNT(*) FROM rc_idempotency"),
                "and the key was never even inserted");
    }

    // ------------------------------------------------------------------
    // Completion — stage two
    // ------------------------------------------------------------------

    @Test
    @DisplayName("completing a recharge posts the income voucher and balances")
    void completePostsIncome() {
        var settled = settlement.settle(
                List.of(tv("DISHTV", "240.00", null)),
                List.of(cash("250.00", "250.00")),
                staffUserId, "key-complete-1");
        long rechargeId = settled.recharges().get(0).getRechargeId();

        RcRecharge completed = fulfilment.markCompleted(rechargeId, "VND-123", null, staffUserId);

        assertEquals(RechargeStatus.COMPLETED, completed.getStatusCode());
        assertEquals("VND-123", completed.getExternalRef());
        assertNotNull(completed.getStatusChangedAt());

        // The brief's worked example, read back from the database.
        String key = RechargeLedgerPostings.completionKey(rechargeId);
        assertEquals("250.00", legAmount(key, "RCHG_PENDING", "DR_AMOUNT"));
        assertEquals("231.36", legAmount(key, "FLOAT_A1TOPUP", "CR_AMOUNT"));
        assertEquals("8.64", legAmount(key, "INC_RCHG_COMM", "CR_AMOUNT"));
        assertEquals("10.00", legAmount(key, "INC_RCHG_SURCHARGE", "CR_AMOUNT"));

        // Both vouchers exist and both balance. V23's trigger 1 would have
        // refused an unbalanced one at seal time, so this confirms the trigger
        // fired rather than merely existed.
        assertEquals(2L, count("SELECT COUNT(*) FROM acc_voucher "
                + "WHERE SOURCE_TYPE = 'RECHARGE_SALE' AND SEALED_AT IS NOT NULL"));
        assertEquals("0.00", scalar(
                "SELECT COALESCE(SUM(DR_AMOUNT) - SUM(CR_AMOUNT), 0) FROM acc_voucher_line"));
    }

    @Test
    @DisplayName("CASH_RECHARGE and RCHG_PENDING net to zero once the recharge completes")
    void pendingClearsOnCompletion() {
        // The identity the two-stage design exists for: cash is recorded the
        // instant it is collected, and RCHG_PENDING holds it only until the
        // recharge happens. After completion the liability is clear and the money
        // has become float plus income.
        var settled = settlement.settle(
                List.of(mobile("ARTL", "199.00", null)),
                List.of(cash("199.00", "200.00")),
                staffUserId, "key-pending-1");

        assertEquals("199.00", balanceOf("RCHG_PENDING", "CR"));

        fulfilment.markCompleted(settled.recharges().get(0).getRechargeId(),
                null, null, staffUserId);

        assertEquals("199.00", balanceOf("RCHG_PENDING", "DR"));
        assertEquals("0.00", scalar("SELECT COALESCE(SUM(CR_AMOUNT) - SUM(DR_AMOUNT), 0) "
                + "FROM acc_voucher_line WHERE ACCOUNT_CODE = 'RCHG_PENDING'"));
        assertEquals("199.00", balanceOf("CASH_RECHARGE", "DR"),
                "the cash leg is untouched by the completion");
    }

    @Test
    @DisplayName("a failed recharge posts nothing, and the money stays pending")
    void failurePostsNothing() {
        // log §7.2's biggest saving: "FAILED needs no reversing entry at all."
        var settled = settlement.settle(
                List.of(mobile("RJIO", "239.00", null)),
                List.of(cash("239.00", "239.00")),
                staffUserId, "key-fail-1");

        long vouchersBefore = count("SELECT COUNT(*) FROM acc_voucher");

        RcRecharge failed = fulfilment.markFailed(settled.recharges().get(0).getRechargeId(),
                "operator declined", staffUserId);

        assertEquals(RechargeStatus.FAILED, failed.getStatusCode());
        assertEquals("operator declined", failed.getFailureReason());
        assertEquals(vouchersBefore, count("SELECT COUNT(*) FROM acc_voucher"),
                "no reversal to write, because nothing had been recognised");
        assertEquals("239.00", balanceOf("RCHG_PENDING", "CR"),
                "the money is held, which is what the end-of-day warning reads");
    }

    @Test
    @DisplayName("completing twice writes once and posts once")
    void completionIsIdempotent() {
        var settled = settlement.settle(
                List.of(mobile("ARTL", "299.00", null)),
                List.of(cash("299.00", "300.00")),
                staffUserId, "key-idem-complete");
        long rechargeId = settled.recharges().get(0).getRechargeId();

        fulfilment.markCompleted(rechargeId, null, null, staffUserId);
        fulfilment.markCompleted(rechargeId, null, null, staffUserId);

        assertEquals(1L, count("SELECT COUNT(*) FROM acc_voucher WHERE IDEMPOTENCY_KEY = '"
                        + RechargeLedgerPostings.completionKey(rechargeId) + "'"),
                "§7's threat 2: the transition is naturally idempotent, so no key is needed");
        assertEquals(1L, count("SELECT COUNT(*) FROM rc_recharge_status_hist "
                        + "WHERE RECHARGE_ID = " + rechargeId + " AND TO_STATUS = 'COMPLETED'"),
                "and the history gained one row, not two");
    }

    @Test
    @DisplayName("the route may be overridden at completion, and it changes the wallet")
    void routeOverrideAtCompletion() {
        // log §14.6: the route is captured at completion because at payment time
        // nobody knows which connection will be used. Jio defaults to DIRECT;
        // putting it through A1Topup must move the float AND the rate.
        var settled = settlement.settle(
                List.of(mobile("RJIO", "299.00", null)),
                List.of(cash("299.00", "299.00")),
                staffUserId, "key-route-1");
        long rechargeId = settled.recharges().get(0).getRechargeId();

        assertEquals("DIRECT", settled.recharges().get(0).getRouteCode());

        fulfilment.markCompleted(rechargeId, null, "A1TOPUP", staffUserId);

        String key = RechargeLedgerPostings.completionKey(rechargeId);
        assertEquals("A1TOPUP", scalar("SELECT ROUTE_CODE FROM rc_recharge "
                + "WHERE RECHARGE_ID = " + rechargeId));
        assertEquals("296.61", legAmount(key, "FLOAT_A1TOPUP", "CR_AMOUNT"),
                "0.8% through A1Topup, not the 2.5% direct rate");
        assertEquals("2.39", legAmount(key, "INC_RCHG_COMM", "CR_AMOUNT"));
    }

    @Test
    @DisplayName("an illegal transition is a 409 and writes nothing")
    void illegalTransition() {
        var settled = settlement.settle(
                List.of(mobile("ARTL", "199.00", null)),
                List.of(cash("199.00", "199.00")),
                staffUserId, "key-illegal-1");
        long rechargeId = settled.recharges().get(0).getRechargeId();

        fulfilment.markCompleted(rechargeId, null, null, staffUserId);
        fulfilment.voidRecharge(rechargeId, "entered twice", staffUserId);

        // VOIDED is terminal — nothing follows it.
        var ex = assertThrows(
                com.mangle.retailshopapp.recharge.exception.IllegalStatusTransitionException.class,
                () -> fulfilment.markFailed(rechargeId, "late", staffUserId));
        assertEquals(409, ex.status());
    }

    @Test
    @DisplayName("voiding needs a reason")
    void voidNeedsReason() {
        // log §7.1: the mandatory reason plus float reconciliation is the ONLY
        // control against using a void to refund a delivered recharge. There is
        // no second approver, because FRD §3 fixes v1 as single-user.
        var settled = settlement.settle(
                List.of(mobile("ARTL", "199.00", null)),
                List.of(cash("199.00", "199.00")),
                staffUserId, "key-void-1");
        long rechargeId = settled.recharges().get(0).getRechargeId();
        fulfilment.markCompleted(rechargeId, null, null, staffUserId);

        assertThrows(com.mangle.retailshopapp.recharge.exception.ReasonRequiredException.class,
                () -> fulfilment.voidRecharge(rechargeId, "   ", staffUserId));
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static RequestedRecharge mobile(String operator, String amount, String surcharge) {
        return new RequestedRecharge(ServiceType.MOBILE, "9000000002", operator,
                new BigDecimal(amount), surcharge == null ? null : new BigDecimal(surcharge));
    }

    private static RequestedRecharge tv(String operator, String amount, String surcharge) {
        return new RequestedRecharge(ServiceType.TV, "02700000001", operator,
                new BigDecimal(amount), surcharge == null ? null : new BigDecimal(surcharge));
    }

    private static RequestedTender cash(String amount, String tendered) {
        return new RequestedTender(SettlementMethod.CASH, new BigDecimal(amount),
                new BigDecimal(tendered), null, null, null);
    }

    private static RequestedTender credit(String amount, Integer customerId) {
        return new RequestedTender(SettlementMethod.CREDIT, new BigDecimal(amount), null, null,
                customerId, "will pay Friday");
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

    /** One leg's amount on the voucher carrying {@code idempotencyKey}. */
    private String legAmount(String idempotencyKey, String accountCode, String side) {
        return scalar("SELECT COALESCE(SUM(l." + side + "), 0) FROM acc_voucher_line l "
                + "JOIN acc_voucher v ON v.VOUCHER_ID = l.VOUCHER_ID "
                + "WHERE v.IDEMPOTENCY_KEY = '" + idempotencyKey + "' "
                + "AND l.ACCOUNT_CODE = '" + accountCode + "'");
    }

    /** One account's total on one side, across every sealed voucher. */
    private String balanceOf(String accountCode, String side) {
        return scalar("SELECT COALESCE(SUM(l." + side + "_AMOUNT), 0) FROM acc_voucher_line l "
                + "JOIN acc_voucher v ON v.VOUCHER_ID = l.VOUCHER_ID "
                + "WHERE v.SEALED_AT IS NOT NULL AND l.ACCOUNT_CODE = '" + accountCode + "'");
    }

    private long count(String sql) {
        em.flush();
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }

    private String scalar(String sql) {
        em.flush();
        Object v = em.createNativeQuery(sql).getSingleResult();
        return v == null ? null : v.toString();
    }
}
