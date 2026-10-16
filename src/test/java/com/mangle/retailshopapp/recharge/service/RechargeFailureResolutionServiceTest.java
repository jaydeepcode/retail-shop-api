package com.mangle.retailshopapp.recharge.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.mangle.retailshopapp.accounting.LedgerPostingService;
import com.mangle.retailshopapp.recharge.domain.RechargeStatus;
import com.mangle.retailshopapp.recharge.domain.ResidualMode;
import com.mangle.retailshopapp.recharge.domain.ServiceType;
import com.mangle.retailshopapp.recharge.domain.SettlementMethod;
import com.mangle.retailshopapp.recharge.exception.FailureAlreadyResolvedException;
import com.mangle.retailshopapp.recharge.exception.IllegalStatusTransitionException;
import com.mangle.retailshopapp.recharge.exception.InvalidTenderException;
import com.mangle.retailshopapp.recharge.exception.RefundModeNotAvailableException;
import com.mangle.retailshopapp.recharge.exception.UnsettledDifferenceException;
import com.mangle.retailshopapp.recharge.model.RcRecharge;
import com.mangle.retailshopapp.recharge.service.RechargeFailureResolutionService.Settlement;
import com.mangle.retailshopapp.recharge.service.RechargeSettlementService.RequestedRecharge;
import com.mangle.retailshopapp.recharge.service.RechargeSettlementService.RequestedTender;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resolving a failure, end to end against real MySQL (#12, #13).
 *
 * <p>The brief's rule, and this slice has relearned it four times: "Run it
 * before claiming it works." Every assertion below is read back from the
 * database rather than from the object the service returned.
 *
 * <p>⚠ THE CLOCK IS MUTABLE HERE, which no other test in this module needs. The
 * one shape that distinguishes a correct day summary from a plausible one is a
 * retry resolved on a DIFFERENT DAY from the sale — §7.2's "midnight straddle" —
 * and a test that cannot move the clock asserts that case against the day it
 * happens to run. It only ever moves FORWARD: V25 seeds every commission rate
 * with {@code EFFECTIVE_FROM = CURDATE()}, so a recharge dated before today has
 * no rate (implementation-plan.md §0.6's fourth defect).
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({RechargeSettlementService.class, RechargeFulfilmentService.class,
         RechargeFailureResolutionService.class, RechargeHistoryService.class,
         RechargeLedgerPostings.class, LedgerPostingService.class,
         OperatorConfigService.class, CustomerDirectoryService.class,
         CreditLedgerService.class, SettingService.class, FinancialYears.class,
         RechargeFailureResolutionServiceTest.MovableClock.class})
@DisplayName("Retry and refund, end to end")
class RechargeFailureResolutionServiceTest {

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
        // V29 is the head this slice adds. The cap still excludes V13-V19.
        registry.add("spring.flyway.target", () -> "29");
        registry.add("spring.flyway.out-of-order", () -> "false");
    }

    /** A clock the test can move forward, for the midnight-straddle cases. */
    @TestConfiguration
    static class MovableClock {
        @Bean
        Clock clock() {
            return new Movable();
        }
    }

    static final class Movable extends Clock {
        private Instant now = Instant.now();

        @Override public ZoneId getZone() {
            return ZoneId.systemDefault();
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return now;
        }

        void advanceOneDay() {
            now = now.plus(Duration.ofDays(1));
        }

        void reset() {
            now = Instant.now();
        }
    }

    @Autowired private RechargeSettlementService settlement;
    @Autowired private RechargeFulfilmentService fulfilment;
    @Autowired private RechargeFailureResolutionService resolution;
    @Autowired private RechargeHistoryService historyService;
    @Autowired private CreditLedgerService credit;
    @Autowired private Clock clock;
    @Autowired private EntityManager em;

    private int staffUserId;

    @BeforeEach
    void seedStaff() {
        ((Movable) clock).reset();
        staffUserId = insertStaffUser();
    }

    // ------------------------------------------------------------------
    // #12 — the retry itself
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a retry at the same amount reuses the payment and posts nothing")
    void retrySameAmount() {
        long failedId = settleThenFail("RJIO", "299.00", "key-retry-same");
        RcRecharge original = find(failedId);
        long vouchersBefore = count("SELECT COUNT(*) FROM acc_voucher");

        var retried = resolution.retry(failedId, new BigDecimal("299.00"), null, null, staffUserId);

        RcRecharge retry = find(retried.retry().getRechargeId());
        assertEquals(RechargeStatus.AWAITING_RECHARGE, retry.getStatusCode());
        assertEquals(failedId, retry.getFailedRechargeId(), "linked to the attempt it replaces");

        // §3.3's invariant, read back from the database: no new payment and no
        // second item line. This is the one fact requirement 5 is really about.
        assertEquals(original.getTxnId(), retry.getTxnId(), "the SAME payment");
        assertEquals(original.getSeqNo(), retry.getSeqNo(), "the SAME item line");
        assertEquals(1, count("SELECT COUNT(*) FROM rc_txn_details WHERE TXN_ID = "
                + original.getTxnId()), "still one item line on the basket");
        assertEquals(1, count("SELECT COUNT(*) FROM rc_txn_payment WHERE TXN_ID = "
                + original.getTxnId()), "and still one tender");

        assertEquals(RechargeStatus.RETRIED, find(failedId).getStatusCode());
        assertEquals(vouchersBefore, count("SELECT COUNT(*) FROM acc_voucher"),
                "log §7.2: a same-amount retry posts NOTHING — the new row posts "
                        + "at its own completion");
    }

    @Test
    @DisplayName("the retry carries the original's surcharge, route and number")
    void retryCarriesTheLine() {
        long failedId = settleThenFail(ServiceType.TV, "DISHTV", "02700000001", "240.00",
                "250.00", "key-retry-carry");

        var retried = resolution.retry(failedId, new BigDecimal("200.00"),
                new Settlement(ResidualMode.CASH, null, null), null, staffUserId);

        RcRecharge retry = find(retried.retry().getRechargeId());
        assertEquals(new BigDecimal("10.00"), retry.getSurcharge(),
                "the shop's fee was charged for taking the sale and does not move");
        assertEquals(new BigDecimal("200.00"), retry.getAmount());
        assertEquals("02700000001", retry.getTargetNumber());
        assertEquals(ServiceType.TV, retry.getRechargeKind());
        assertEquals(find(failedId).getRouteCode(), retry.getRouteCode(),
                "the original's route, not today's default — the route is a "
                        + "completion-time decision and is overridable there");
    }

    // ------------------------------------------------------------------
    // #12 — the difference, both directions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a lower retry hands the difference back in cash")
    void retryLowerCashBack() {
        long failedId = settleThenFail("RJIO", "299.00", "key-retry-lower");

        var retried = resolution.retry(failedId, new BigDecimal("199.00"),
                new Settlement(ResidualMode.CASH, null, null), null, staffUserId);

        assertEquals(new BigDecimal("100.00"), retried.plan().differenceOwed());
        assertEquals(ResidualMode.CASH, retried.differenceSettledAs());

        // log §7.4's first row, read back leg by leg.
        String key = RechargeLedgerPostings.residualKey(failedId);
        assertEquals("100.00", legAmount(key, "RCHG_PENDING", "DR_AMOUNT"));
        assertEquals("100.00", legAmount(key, "CASH_RECHARGE", "CR_AMOUNT"));

        // What remains held is exactly what the retry will consume.
        assertEquals("199.00", netOf("RCHG_PENDING", "CR_MINUS_DR"));
        assertEquals("199.00", netOf("CASH_RECHARGE", "DR_MINUS_CR"),
                "the drawer is down by the hundred that went back over the counter");
    }

    @Test
    @DisplayName("a lower retry can leave the difference on the customer's account")
    void retryLowerKeptAsAdvance() {
        // log §2.8's account, and the case is evidenced in live data: two
        // customers already carry +Rs 37 and +Rs 15 from exactly this.
        int customerId = insertCustomer("Asha", "Keeps", "9000000011");
        long failedId = settleThenFail("RJIO", "299.00", "key-retry-advance");

        var retried = resolution.retry(failedId, new BigDecimal("199.00"),
                new Settlement(ResidualMode.KEEP_AS_ADVANCE, customerId, "keep it"),
                null, staffUserId);

        String key = RechargeLedgerPostings.residualKey(failedId);
        assertEquals("100.00", legAmount(key, "RCHG_PENDING", "DR_AMOUNT"));
        assertEquals("100.00", legAmount(key, "ADV_RECHARGE", "CR_AMOUNT"));
        assertEquals(customerId, Integer.parseInt(scalar(
                "SELECT l.PARTY_CUST_ID FROM acc_voucher_line l "
                        + "JOIN acc_voucher v ON v.VOUCHER_ID = l.VOUCHER_ID "
                        + "WHERE v.IDEMPOTENCY_KEY = '" + key + "' "
                        + "AND l.ACCOUNT_CODE = 'ADV_RECHARGE'")),
                "ADV_RECHARGE has REQUIRES_PARTY = TRUE; a leg without one cannot post");

        assertEquals("299.00", netOf("CASH_RECHARGE", "DR_MINUS_CR"),
                "no cash left the drawer — the customer chose to leave it with the shop");
        assertNotNull(retried.creditPosition());
    }

    @Test
    @DisplayName("a higher retry takes the extra, appends a tender line and raises the total")
    void retryHigherTopUp() {
        long failedId = settleThenFail("RJIO", "299.00", "key-retry-higher");
        int paymentId = find(failedId).getTxnId();

        var retried = resolution.retry(failedId, new BigDecimal("349.00"), null,
                new Settlement(null, SettlementMethod.CASH, null, null), staffUserId);

        assertEquals(new BigDecimal("50.00"), retried.plan().topUpDue());
        assertEquals(SettlementMethod.CASH, retried.topUpTakenAs());

        // The ledger mirror of the residual.
        String key = RechargeLedgerPostings.topUpKey(failedId);
        assertEquals("50.00", legAmount(key, "CASH_RECHARGE", "DR_AMOUNT"));
        assertEquals("50.00", legAmount(key, "RCHG_PENDING", "CR_AMOUNT"));
        assertEquals("349.00", netOf("RCHG_PENDING", "CR_MINUS_DR"),
                "held now covers what the retry will consume");

        // And the payment, which is what the user's question was about.
        assertEquals(2, count("SELECT COUNT(*) FROM rc_txn_payment WHERE TXN_ID = " + paymentId),
                "a second tender line on the ORIGINAL payment, not a new payment");
        assertEquals("349.00", scalar(
                "SELECT TXN_TOTAL_AMT FROM rc_txn_header WHERE TXN_ID = " + paymentId),
                "V12: the lines SUM TO txn_total_amt, so the total moves with them");
        assertEquals(1, count("SELECT COUNT(*) FROM rc_txn_header WHERE TXN_ID = " + paymentId),
                "still exactly one payment for this visit");
        assertEquals(1, count("SELECT COUNT(*) FROM rc_txn_details WHERE TXN_ID = " + paymentId),
                "and still one item line — a top-up is money, not a second sale");
    }

    @Test
    @DisplayName("a top-up collected the next day lands on the next day's tender mix")
    void topUpAcrossMidnight() {
        // ⚠ THE CASE THE tenderMixOn CHANGE EXISTS FOR, and the one every
        // same-day fixture steps around. The tender line hangs off YESTERDAY's
        // header; dated by the header it would be added to yesterday's mix while
        // its cash posting landed today, breaking §7.1(c)'s cross-check on both
        // days — and corrupting a day already counted against the drawer.
        LocalDate day1 = LocalDate.now(clock);
        long failedId = settleThenFail("RJIO", "299.00", "key-retry-midnight");

        ((Movable) clock).advanceOneDay();
        LocalDate day2 = LocalDate.now(clock);
        assertNotEquals(day1, day2);

        resolution.retry(failedId, new BigDecimal("349.00"), null,
                new Settlement(null, SettlementMethod.CASH, null, null), staffUserId);

        var summary1 = historyService.daySummary(day1);
        var summary2 = historyService.daySummary(day2);

        // The identity §7.1(c) names: two INDEPENDENT sources of the same
        // number, and they must agree. On BOTH days.
        assertEquals(summary1.cashCollectedToday(), summary1.tenderMix().get(SettlementMethod.CASH),
                "day 1: the ledger and the tender lines agree");
        assertEquals(summary2.cashCollectedToday(), summary2.tenderMix().get(SettlementMethod.CASH),
                "day 2: and the top-up is counted here, not back on day 1");
        assertEquals(new BigDecimal("299.00"), summary1.cashCollectedToday());
        assertEquals(new BigDecimal("50.00"), summary2.cashCollectedToday());
    }

    // ------------------------------------------------------------------
    // #12 — what gets refused
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a lower retry with no settlement is refused before anything is written")
    void unsettledDifference() {
        long failedId = settleThenFail("RJIO", "299.00", "key-retry-unsettled");
        long rechargesBefore = count("SELECT COUNT(*) FROM rc_recharge");

        assertThrows(UnsettledDifferenceException.class,
                () -> resolution.retry(failedId, new BigDecimal("199.00"), null, null,
                        staffUserId));

        assertEquals(rechargesBefore, count("SELECT COUNT(*) FROM rc_recharge"),
                "no retry row");
        assertEquals(RechargeStatus.FAILED, find(failedId).getStatusCode(),
                "and the failure is still open — §5.1: all or nothing");
    }

    @Test
    @DisplayName("a settlement sent when nothing is owed is refused, not ignored")
    void straySettlement() {
        // Dropping it silently would leave the staff member believing a hundred
        // rupees went back over the counter while the drawer says otherwise.
        long failedId = settleThenFail("RJIO", "299.00", "key-retry-stray");

        assertThrows(UnsettledDifferenceException.class,
                () -> resolution.retry(failedId, new BigDecimal("299.00"),
                        new Settlement(ResidualMode.CASH, null, null), null, staffUserId));
    }

    @Test
    @DisplayName("a second retry of one failure is refused")
    void secondRetry() {
        long failedId = settleThenFail("RJIO", "299.00", "key-retry-twice");
        resolution.retry(failedId, new BigDecimal("299.00"), null, null, staffUserId);

        // The parent is terminal now, so the state machine catches it first.
        // V29's unique key is the guard behind that, for callers that do not
        // come through here.
        assertThrows(FailureAlreadyResolvedException.class,
                () -> resolution.retry(failedId, new BigDecimal("299.00"), null, null,
                        staffUserId));
    }

    @Test
    @DisplayName("the database refuses a second retry even with the status check bypassed")
    void uniqueRetryKeyExists() {
        // §7 threat 3 chose a DB key over an application check, and V12 left the
        // index non-unique. Asserting the constraint itself rather than the
        // service's behaviour, because the service passing proves only the
        // service.
        assertEquals(0, count(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rc_recharge' "
                        + "AND INDEX_NAME = 'idx_rech_retry'"),
                "V29 drops the plain index it replaces");
        assertEquals(1, count(
                "SELECT COUNT(DISTINCT INDEX_NAME) FROM INFORMATION_SCHEMA.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rc_recharge' "
                        + "AND COLUMN_NAME = 'FAILED_RECHARGE_ID' AND NON_UNIQUE = 0"),
                "and leaves exactly one UNIQUE index on FAILED_RECHARGE_ID");
    }

    @Test
    @DisplayName("retrying something that never failed is refused")
    void retryOfANonFailure() {
        var settled = settlement.settle(
                List.of(mobile("RJIO", "299.00")), List.of(cash("299.00", "300.00")),
                staffUserId, "key-retry-awaiting");
        long id = settled.recharges().get(0).getRechargeId();

        assertThrows(IllegalStatusTransitionException.class,
                () -> resolution.retry(id, new BigDecimal("199.00"), null, null, staffUserId));
    }

    // ------------------------------------------------------------------
    // #12 — the chain completes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the retry completes at ITS amount, and the sale ends square")
    void retryThenComplete() {
        // log §7.2's worked example in miniature: cash at settlement, income at
        // the RETRY's amount and the retry's rate, and RCHG_PENDING empty when
        // the whole life of the sale is over.
        long failedId = settleThenFail("BSNL", "147.00", "key-retry-complete");

        var retried = resolution.retry(failedId, new BigDecimal("100.00"),
                new Settlement(ResidualMode.CASH, null, null), null, staffUserId);
        long retryId = retried.retry().getRechargeId();

        fulfilment.markCompleted(retryId, "VND-RETRY", null, staffUserId);

        // BSNL on A1TOPUP is 3.0%: 100 - round(100 x 0.97, 2) = 3.00.
        String key = RechargeLedgerPostings.completionKey(retryId);
        assertEquals("100.00", legAmount(key, "RCHG_PENDING", "DR_AMOUNT"));
        assertEquals("97.00", legAmount(key, "FLOAT_A1TOPUP", "CR_AMOUNT"));
        assertEquals("3.00", legAmount(key, "INC_RCHG_COMM", "CR_AMOUNT"));

        assertEquals("0.00", netOf("RCHG_PENDING", "CR_MINUS_DR"),
                "47 handed back plus 100 consumed equals the 147 collected: "
                        + "nothing is left held against this sale");
        assertEquals(RechargeStatus.COMPLETED, find(retryId).getStatusCode());
        assertEquals(RechargeStatus.RETRIED, find(failedId).getStatusCode());
    }

    // ------------------------------------------------------------------
    // #13 — refund
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a cash refund returns the whole line total and clears what was held")
    void refundCash() {
        long failedId = settleThenFail(ServiceType.TV, "DISHTV", "02700000001", "240.00",
                "250.00", "key-refund-cash");

        var refunded = resolution.refund(failedId, new BigDecimal("250.00"), ResidualMode.CASH,
                null, staffUserId);

        assertEquals(new BigDecimal("250.00"), refunded.amount(),
                "the surcharge is refunded too — the fee was for a recharge that "
                        + "did not happen, and the whole 250 was held");
        String key = RechargeLedgerPostings.residualKey(failedId);
        assertEquals("250.00", legAmount(key, "RCHG_PENDING", "DR_AMOUNT"));
        assertEquals("250.00", legAmount(key, "CASH_RECHARGE", "CR_AMOUNT"));

        assertEquals("0.00", netOf("RCHG_PENDING", "CR_MINUS_DR"));
        assertEquals("0.00", netOf("CASH_RECHARGE", "DR_MINUS_CR"),
                "the drawer is back where it started");
        assertEquals(RechargeStatus.REFUNDED, find(failedId).getStatusCode());
        assertNull(refunded.creditPosition(), "a cash refund names nobody");
    }

    @Test
    @DisplayName("a partial refund is refused, because REFUNDED is terminal")
    void refundMustBeWhole() {
        long failedId = settleThenFail("RJIO", "299.00", "key-refund-partial");

        assertThrows(UnsettledDifferenceException.class,
                () -> resolution.refund(failedId, new BigDecimal("200.00"), ResidualMode.CASH,
                        null, staffUserId));
        assertEquals(RechargeStatus.FAILED, find(failedId).getStatusCode());
    }

    @Test
    @DisplayName("a credit sale's refund reduces the tab and cannot be taken in cash")
    void refundOnCredit() {
        int customerId = insertCustomer("Bala", "Owes", "9000000012");
        var settled = settlement.settle(
                List.of(mobile("RJIO", "299.00")),
                List.of(new RequestedTender(SettlementMethod.CREDIT, new BigDecimal("299.00"),
                        null, null, customerId, "pay Friday")),
                staffUserId, "key-refund-credit");
        long failedId = settled.recharges().get(0).getRechargeId();
        fulfilment.markFailed(failedId, "operator down", staffUserId);

        assertEquals(new BigDecimal("299.00"), credit.positionOf(customerId).owed());

        // log §7.4: "On a credit original no cash was ever collected, so there
        // is no choice to offer."
        assertThrows(RefundModeNotAvailableException.class,
                () -> resolution.refund(failedId, new BigDecimal("299.00"), ResidualMode.CASH,
                        null, staffUserId));

        var refunded = resolution.refund(failedId, new BigDecimal("299.00"),
                ResidualMode.CREDIT_REDUCTION, null, staffUserId);

        String key = RechargeLedgerPostings.residualKey(failedId);
        assertEquals("299.00", legAmount(key, "RCHG_PENDING", "DR_AMOUNT"));
        assertEquals("299.00", legAmount(key, "AR_RECHARGE", "CR_AMOUNT"));

        assertEquals(1, count("SELECT COUNT(*) FROM rc_credit_req "
                + "WHERE CUST_ID = " + customerId + " AND REQ_TYPE = 'DPAM'"),
                "a DPAM row with a positive req_amt — the tally is append-only, "
                        + "so nothing is edited away");
        assertEquals(new BigDecimal("0.00"), credit.positionOf(customerId).owed(),
                "and the tab is clear");
        assertEquals(new BigDecimal("0.00"), refunded.creditPosition().owed());
    }

    @Test
    @DisplayName("a credit reduction is refused where the sale took no credit")
    void creditReductionNeedsACreditLine() {
        // design-api.md:789 — "only where credit was extended". Reducing a tab
        // this sale never charged would hand back money never taken.
        int customerId = insertCustomer("Chandra", "Uninvolved", "9000000013");
        long failedId = settleThenFail("RJIO", "299.00", "key-refund-nocredit");

        assertThrows(RefundModeNotAvailableException.class,
                () -> resolution.refund(failedId, new BigDecimal("299.00"),
                        ResidualMode.CREDIT_REDUCTION, customerId, staffUserId));
    }

    @Test
    @DisplayName("refunding after COMPLETED -> FAILED takes the income back exactly once")
    void refundAfterCompletionWasReversed() {
        // ⚠ THE FOURTH PATH, and the one that decides whether this service may
        // write a reversal of its own. markFailed already reversed the
        // completion when the status moved (log §7.1), so by the time the refund
        // runs there is nothing recognised left to take back — a second reversal
        // here would un-earn the commission twice.
        long failedId = settleThenComplete("BSNL", "147.00", "key-refund-after-complete");
        fulfilment.markFailed(failedId, "operator reversed it", staffUserId);

        resolution.refund(failedId, new BigDecimal("147.00"), ResidualMode.CASH, null,
                staffUserId);

        assertEquals("0.00", netOf("INC_RCHG_COMM", "CR_MINUS_DR"),
                "commission recognised then un-recognised, once each");
        assertEquals("0.00", netOf("FLOAT_A1TOPUP", "DR_MINUS_CR"),
                "and the float is back");
        assertEquals("0.00", netOf("RCHG_PENDING", "CR_MINUS_DR"));
        assertEquals("0.00", netOf("CASH_RECHARGE", "DR_MINUS_CR"),
                "the customer's money is back in their hand");
    }

    @Test
    @DisplayName("resolving an already-resolved failure is refused")
    void refundTwice() {
        long failedId = settleThenFail("RJIO", "299.00", "key-refund-twice");
        resolution.refund(failedId, new BigDecimal("299.00"), ResidualMode.CASH, null,
                staffUserId);

        assertThrows(FailureAlreadyResolvedException.class,
                () -> resolution.refund(failedId, new BigDecimal("299.00"), ResidualMode.CASH,
                        null, staffUserId));
    }

    @Test
    @DisplayName("on a day with a refund the till identity still holds, with three terms")
    void tillIdentityOnARefundDay() {
        // ⚠ THE DEFECT THIS SLICE FOUND BY RUNNING IT. The day summary's
        // javadoc said cashCollectedToday and tenderMix[CASH] were "two
        // INDEPENDENT sources of the same number" that "must agree". They
        // cannot, once money goes out: ck_txnpay_amount CHECK (AMOUNT > 0)
        // makes a negative tender row unrepresentable, so a refund reduces the
        // ledger figure and leaves the tender figure untouched. Driving one
        // refund through a running server made the two read 849 and 1199.
        //
        // The relation is still assertable, with the outgoing side as its own
        // term — which is also a number the counter wants to see.
        LocalDate today = LocalDate.now(clock);
        long failedId = settleThenFail("RJIO", "299.00", "key-till-refund");
        settleThenComplete("BSNL", "147.00", "key-till-kept");

        resolution.refund(failedId, new BigDecimal("299.00"), ResidualMode.CASH, null,
                staffUserId);

        var summary = historyService.daySummary(today);

        assertEquals(new BigDecimal("446.00"), summary.tenderMix().get(SettlementMethod.CASH),
                "both sales were collected in cash, and neither tender row moves");
        assertEquals(new BigDecimal("299.00"), summary.cashPaidOutToday(),
                "and the refund went back over the counter");
        assertEquals(summary.tenderMix().get(SettlementMethod.CASH)
                        .subtract(summary.cashPaidOutToday()),
                summary.cashCollectedToday(),
                "collected = tendered - paid out, which is the identity that survives a refund");
        assertEquals(new BigDecimal("147.00"), summary.cashCollectedToday(),
                "and what is actually left in the drawer from today");
    }

    // ------------------------------------------------------------------
    // Review finding 9 — the guard the refund path depends on
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a payment against a tab cannot be negative or null")
    void receivePaymentIsGuarded() {
        // Finding 9. A negative DPAM row INCREASES the debt, and the tally is
        // append-only, so the row could never be corrected — only offset.
        int customerId = insertCustomer("Devi", "Tab", "9000000014");

        assertThrows(InvalidTenderException.class,
                () -> credit.receivePayment(customerId, new BigDecimal("-50.00"), staffUserId,
                        "wrong way round", null, java.time.LocalDateTime.now(clock)));
        assertThrows(InvalidTenderException.class,
                () -> credit.receivePayment(customerId, null, staffUserId, "nothing at all",
                        null, java.time.LocalDateTime.now(clock)));

        assertEquals(0, count("SELECT COUNT(*) FROM rc_credit_req WHERE CUST_ID = " + customerId),
                "and nothing was appended either time");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private long settleThenFail(String operator, String amount, String key) {
        return settleThenFail(ServiceType.MOBILE, operator, "9000000002", amount, amount, key);
    }

    private long settleThenFail(ServiceType type, String operator, String number,
                                String amount, String paid, String key) {
        var settled = settlement.settle(
                List.of(new RequestedRecharge(type, number, operator, new BigDecimal(amount),
                        null)),
                List.of(cash(paid, paid)), staffUserId, key);
        long id = settled.recharges().get(0).getRechargeId();
        fulfilment.markFailed(id, "operator declined", staffUserId);
        return id;
    }

    private long settleThenComplete(String operator, String amount, String key) {
        var settled = settlement.settle(
                List.of(mobile(operator, amount)), List.of(cash(amount, amount)),
                staffUserId, key);
        long id = settled.recharges().get(0).getRechargeId();
        fulfilment.markCompleted(id, "VND-1", null, staffUserId);
        return id;
    }

    private static RequestedRecharge mobile(String operator, String amount) {
        return new RequestedRecharge(ServiceType.MOBILE, "9000000002", operator,
                new BigDecimal(amount), null);
    }

    private static RequestedTender cash(String amount, String tendered) {
        return new RequestedTender(SettlementMethod.CASH, new BigDecimal(amount),
                new BigDecimal(tendered), null, null, null);
    }

    private RcRecharge find(long rechargeId) {
        em.flush();
        em.clear();
        return em.find(RcRecharge.class, rechargeId);
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

    private String legAmount(String idempotencyKey, String accountCode, String side) {
        return scalar("SELECT COALESCE(SUM(l." + side + "), 0) FROM acc_voucher_line l "
                + "JOIN acc_voucher v ON v.VOUCHER_ID = l.VOUCHER_ID "
                + "WHERE v.IDEMPOTENCY_KEY = '" + idempotencyKey + "' "
                + "AND l.ACCOUNT_CODE = '" + accountCode + "'");
    }

    private String netOf(String accountCode, String side) {
        String expr = "CR_MINUS_DR".equals(side)
                ? "SUM(l.CR_AMOUNT)-SUM(l.DR_AMOUNT)"
                : "SUM(l.DR_AMOUNT)-SUM(l.CR_AMOUNT)";
        return scalar("SELECT COALESCE(" + expr + ", 0) FROM acc_voucher_line l "
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
