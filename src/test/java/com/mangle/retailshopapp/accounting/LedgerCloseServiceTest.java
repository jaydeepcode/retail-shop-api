package com.mangle.retailshopapp.accounting;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.mangle.retailshopapp.accounting.model.request.ExplainedVariance;
import com.mangle.retailshopapp.accounting.model.request.ExplainedVariance.VarianceDirection;
import com.mangle.retailshopapp.accounting.model.request.ExplainedVariance.VarianceReason;
import com.mangle.retailshopapp.accounting.model.request.PoolCloseRequest;
import com.mangle.retailshopapp.accounting.model.request.ReconciliationRequest;
import com.mangle.retailshopapp.accounting.model.response.ClosePreviewResponse;
import com.mangle.retailshopapp.accounting.model.response.PoolCloseResponse;
import com.mangle.retailshopapp.accounting.model.response.PoolCloseResponse.Sweep.SweepStatus;
import com.mangle.retailshopapp.accounting.model.response.ReconciliationResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The day close, against real MySQL.
 *
 * <p>⚠ THE CONTAINER IS NOT OPTIONAL HERE, and not for the usual reason. Three
 * of the behaviours below exist only in a real database:
 * {@code uk_recon_account_date} is the whole replay guard,
 * {@code ck_line_nonzero CHECK (DR_AMOUNT + CR_AMOUNT > 0)} is what makes a zero
 * sweep leg a FAILED CLOSE rather than a junk voucher, and {@code VARIANCE_AMOUNT}
 * is a generated STORED column that only the database computes. H2 has none of
 * them.
 *
 * <p>Flyway runs to <b>34</b>, because {@code INC_SUNDRY_SHOP} is what V34 added
 * and it is the only {@code INCOME} account with {@code ALLOW_MANUAL = TRUE} —
 * without it {@code direction: "IN"} has no account it can legally name and two
 * of artboard 9's four buttons cannot be tested at all.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({LedgerCloseService.class, LedgerPostingService.class,
        LedgerCloseServiceTest.FixedClock.class})
@DisplayName("The day close, against MySQL")
class LedgerCloseServiceTest {

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            // Evening, and derived from today rather than pinned to a date:
            // RechargeLedgerPostingsTest records what pinning one costs when a
            // migration seeds EFFECTIVE_FROM = CURDATE().
            return Clock.fixed(LocalDate.now().atTime(20, 40).atZone(ZoneId.systemDefault())
                    .toInstant(), ZoneId.systemDefault());
        }
    }

    /** See LedgerMigrationTest for why the log_bin flag is required, not tuning. */
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
        // The migrations own the schema. ddl-auto must stay off or Hibernate
        // would create its own FK-less tables and the IF NOT EXISTS guards would
        // then skip the real ones (implementation-plan.md §0.1).
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.MySQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("spring.flyway.baseline-version", () -> "8");
        // 34, matching spring.flyway.target. The cap moved 33 -> 34 with the
        // owner's decision of 2026-11-02 for INC_SUNDRY_SHOP and nothing else;
        // V35/V36 are S6's opening balances (local-dev/OWNER-GATE.md).
        registry.add("spring.flyway.target", () -> "34");
        registry.add("spring.flyway.out-of-order", () -> "false");
    }

    @Autowired
    private LedgerCloseService service;

    @Autowired
    private JdbcTemplate jdbc;

    private static final LocalDate TODAY = LocalDate.now();

    /**
     * ⚠ In foreign-key order, and it has to be: {@code acc_reconciliation} and
     * {@code acc_voucher_line} both point at {@code acc_voucher}, so the parent
     * goes last. Scoped to the three tables these tests write — the chart of
     * accounts and the pool membership are seeded by the migrations and must
     * survive, since every assertion here reads them.
     */
    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM acc_reconciliation");
        jdbc.update("DELETE FROM acc_voucher_line");
        jdbc.update("DELETE FROM acc_float_movement");
        jdbc.update("DELETE FROM acc_voucher");
    }

    // ------------------------------------------------------------------ setup

    @Autowired
    private LedgerPostingService postings;

    @Autowired
    private TransactionTemplate tx;

    /**
     * Gives an account some takings, through the real posting service.
     *
     * <p>A {@code SALES} voucher, so the balance the close reads is one the
     * ledger actually derived through V26's view rather than a row poked in
     * behind it.
     *
     * <p>Wrapped in a {@code TransactionTemplate} because
     * {@link LedgerPostingService#post} is {@code MANDATORY} — §5.1 calls that
     * "the single most valuable line in this design" and it is not to be relaxed
     * for a test's convenience. The template supplies the transaction a real
     * caller would already be inside.
     */
    private long postTakings(String tillCode, String incomeCode, String amount, LocalDate on) {
        return post(new VoucherDraft(
                "SALES", on, on.atTime(11, 15), "takings", "RECHARGE_SALE", null,
                null, null, null,
                List.of(VoucherLeg.debit(tillCode, new BigDecimal(amount)),
                        VoucherLeg.credit(incomeCode, new BigDecimal(amount)))));
    }

    private long post(VoucherDraft draft) {
        return tx.execute(status -> postings.post(draft).voucherId());
    }

    private BigDecimal balanceOf(String code) {
        return jdbc.queryForObject(
                "SELECT BALANCE FROM acc_account_balance WHERE ACCOUNT_CODE = ?",
                BigDecimal.class, code);
    }

    private static PoolCloseRequest count(String counted) {
        return new PoolCloseRequest(TODAY, new BigDecimal(counted), null, null);
    }

    // ==================================================================
    // The ordering — the one subtle decision in this slice
    // ==================================================================

    @Nested
    @DisplayName("⚠ the acc_reconciliation rows are written BEFORE the vouchers")
    class Ordering {

        @Test
        @DisplayName("LEDGER_AMOUNT is the balance as it stood before the close, not 0.00")
        void ledgerAmountIsThePreCloseBalance() {
            // THIS IS THE TEST THAT CATCHES A REVERSED ORDERING, and the reason
            // it has to assert the figure rather than a refusal is specific.
            // The older documents said a reversed order would make the close
            // "refuse its own sweep" via trigger 4 — but there IS no trigger 4
            // (removed 2026-10-09, now LedgerCorrectionRules.assertVoucherDeletable),
            // it was BEFORE DELETE even then, and NOTHING has ever checked
            // reconciliation on an insert. So a reversed close posts happily and
            // a test written to the refusal passes either way.
            //
            // ⚠ VERIFIED BY REVERSING IT, 2026-11-15: with postSweep moved ahead
            // of saveAllAndFlush, this fails on "expected: <2847.00> but was:
            // <0.00>" — and the reversed close POSTED HAPPILY, refusing nothing,
            // which is the other half of the correction.
            //
            // What a reversed order actually destroys is the evidence:
            // LEDGER_AMOUNT is "the derived balance as it stood BEFORE the
            // variance journal" (design-ledger.md:584, V22:146-152), the sweep
            // leaves every till at 0.00, and vouchers are append-only — so post
            // the sweep first and every row records 0.00 against the count, the
            // variance reads as the whole day's takings, and what the drawer
            // held is unrecoverable FOREVER.
            postTakings("CASH_RECHARGE", "INC_RCHG_COMM", "2847.00", TODAY);
            postTakings("CASH_TICKET", "INC_TKT_COMM", "545.00", TODAY);

            service.closePool("POOL-1", count("3392.00"), null);

            assertEquals(new BigDecimal("2847.00"),
                    jdbc.queryForObject("SELECT LEDGER_AMOUNT FROM acc_reconciliation "
                            + "WHERE ACCOUNT_CODE = 'CASH_RECHARGE' AND RECON_DATE = ?",
                            BigDecimal.class, TODAY),
                    "the recharge till's pre-close balance, which is gone once the sweep posts");
            assertEquals(new BigDecimal("545.00"),
                    jdbc.queryForObject("SELECT LEDGER_AMOUNT FROM acc_reconciliation "
                            + "WHERE ACCOUNT_CODE = 'CASH_TICKET' AND RECON_DATE = ?",
                            BigDecimal.class, TODAY));

            // And the sweep did happen, so 0.00 was genuinely reachable: the
            // assertion above is not passing merely because nothing moved.
            assertEquals(0, balanceOf("CASH_RECHARGE").compareTo(BigDecimal.ZERO));
            assertEquals(0, balanceOf("CASH_TICKET").compareTo(BigDecimal.ZERO));
            assertEquals(new BigDecimal("3392.00"), balanceOf("CASH_SAFE"));
        }

        @Test
        @DisplayName("the day's earlier vouchers are sealed by the close, and the close's are not")
        void theCloseSealsTheDayButNotItself() {
            long earlier = postTakings("CASH_RECHARGE", "INC_RCHG_COMM", "500.00", TODAY);
            PoolCloseResponse closed = service.closePool("POOL-1", count("500.00"), null);

            // The earlier voucher is now locked against delete-and-repost: FRD
            // §8.3's regime, enforced at LedgerCorrectionRules:88-93. Asserted as
            // the row pattern that rule reads, since the rule itself has its own
            // test in LedgerCorrectionRulesTest.
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM acc_reconciliation r "
                            + " JOIN acc_voucher_line l ON l.ACCOUNT_CODE = r.ACCOUNT_CODE "
                            + " JOIN acc_voucher v ON v.VOUCHER_ID = l.VOUCHER_ID "
                            + "WHERE v.VOUCHER_ID = ? AND r.RECON_DATE >= v.VOUCHER_DATE",
                    Integer.class, earlier));

            // The sweep is dated the same day and is accepted, which is the half
            // of the ordering claim that IS true: nothing checks reconciliation
            // on an insert.
            assertEquals(SweepStatus.POSTED, closed.sweep().status());
        }
    }

    // ==================================================================
    // A zero leg is illegal
    // ==================================================================

    @Nested
    @DisplayName("⚠ a zero leg is illegal, so the sweep names only what took something")
    class ZeroLegs {

        @Test
        @DisplayName("recharge takings and NO ticket booking — the ordinary day")
        void theOrdinaryDay() {
            // ck_line_nonzero CHECK (DR_AMOUNT + CR_AMOUNT > 0) means "crediting
            // each member" FAILS THE WHOLE CLOSE on any day a member took
            // nothing. Bookings are occasional, so this is the normal case, not
            // an edge one (§1.1, added 2026-11-11).
            postTakings("CASH_RECHARGE", "INC_RCHG_COMM", "2847.00", TODAY);

            PoolCloseResponse closed = service.closePool("POOL-1", count("2847.00"), null);

            assertEquals(SweepStatus.POSTED, closed.sweep().status());
            assertEquals(new BigDecimal("2847.00"), closed.sweep().amount());
            assertEquals(2, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM acc_voucher_line WHERE VOUCHER_ID = ?",
                    Integer.class, closed.sweep().voucherId()),
                    "Dr CASH_SAFE and one credit — never a Cr CASH_TICKET 0.00");
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM acc_voucher_line WHERE VOUCHER_ID = ? "
                            + "AND ACCOUNT_CODE = 'CASH_TICKET'",
                    Integer.class, closed.sweep().voucherId()));

            // ⚠ But the row IS written: it is the evidence the count happened,
            // and it is what arms the lock for CASH_TICKET.
            assertEquals(new BigDecimal("0.00"),
                    jdbc.queryForObject("SELECT COUNTED_AMOUNT FROM acc_reconciliation "
                            + "WHERE ACCOUNT_CODE = 'CASH_TICKET' AND RECON_DATE = ?",
                            BigDecimal.class, TODAY));
        }

        @Test
        @DisplayName("⚠ a member driven NEGATIVE by the variance is debited, not refused")
        void negativeMemberIsDebited() {
            // FOUND BY SETTING UP THE REAL CLOSE, not by the tests here. A2 lands
            // the pool's whole variance on the primary member, so any day the
            // shortfall exceeds that member's OWN takings leaves it negative —
            // and two cash bookings with nothing else is exactly that day.
            // Refusing it, which an earlier version of this service did, would
            // make an ordinary evening impossible to close.
            postTakings("CASH_TICKET", "INC_TKT_COMM", "970.00", TODAY);

            PoolCloseResponse closed = service.closePool("POOL-1", count("960.00"), null);

            // Dr EXP_CASH_SHORT 10 / Cr CASH_RECHARGE 10 puts the primary at -10.
            assertEquals(new BigDecimal("10.00"), cr(closed.varianceVoucherId(), "CASH_RECHARGE"));

            // The sweep zeroes BOTH: Dr CASH_SAFE 960 + Dr CASH_RECHARGE 10
            // against Cr CASH_TICKET 970. Balanced, and no zero or negative leg.
            assertEquals(SweepStatus.POSTED, closed.sweep().status());
            assertEquals(new BigDecimal("960.00"), closed.sweep().amount());
            assertEquals(new BigDecimal("10.00"), dr(closed.sweep().voucherId(), "CASH_RECHARGE"));
            assertEquals(new BigDecimal("970.00"), cr(closed.sweep().voucherId(), "CASH_TICKET"));
            assertEquals(new BigDecimal("960.00"), dr(closed.sweep().voucherId(), "CASH_SAFE"));

            assertEquals(0, balanceOf("CASH_RECHARGE").compareTo(BigDecimal.ZERO));
            assertEquals(0, balanceOf("CASH_TICKET").compareTo(BigDecimal.ZERO));
            assertEquals(new BigDecimal("960.00"), balanceOf("CASH_SAFE"));
        }

        @Test
        @DisplayName("⚠ members that cancel out are swept with no CASH_SAFE leg at all")
        void membersCancelOut() {
            // ck_line_nonzero refuses Dr CASH_SAFE 0.00 as readily as a zero
            // credit, but both members still have to be zeroed — so they balance
            // against each other and the safe is left out of the voucher.
            postTakings("CASH_TICKET", "INC_TKT_COMM", "10.00", TODAY);

            PoolCloseResponse closed = service.closePool("POOL-1", count("0.00"), null);

            assertEquals(SweepStatus.POSTED, closed.sweep().status());
            assertEquals(2, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM acc_voucher_line WHERE VOUCHER_ID = ?",
                    Integer.class, closed.sweep().voucherId()));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM acc_voucher_line WHERE VOUCHER_ID = ? "
                            + "AND ACCOUNT_CODE = 'CASH_SAFE'",
                    Integer.class, closed.sweep().voucherId()),
                    "a Dr CASH_SAFE 0.00 would fail the whole close");
            assertEquals(0, balanceOf("CASH_TICKET").compareTo(BigDecimal.ZERO));
        }

        @Test
        @DisplayName("a day that took nothing at all gets its rows and no sweep voucher")
        void everyMemberZero() {
            PoolCloseResponse closed = service.closePool("POOL-1", count("0.00"), null);

            assertEquals(SweepStatus.SKIPPED_ALL_MEMBERS_ZERO, closed.sweep().status());
            assertNull(closed.sweep().voucherId(),
                    "a null voucher id with no status would read as a failure");
            assertEquals(2, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM acc_reconciliation WHERE RECON_DATE = ? "
                            + "AND ACCOUNT_CODE IN ('CASH_RECHARGE','CASH_TICKET')",
                    Integer.class, TODAY),
                    "a day that genuinely took nothing still deserves a recorded count");
        }
    }

    // ==================================================================
    // The variance, in both directions
    // ==================================================================

    @Nested
    @DisplayName("the variance")
    class Variance {

        @Test
        @DisplayName("E17b — short and unexplained: Dr EXP_CASH_SHORT / Cr the primary member")
        void unexplainedShort() {
            postTakings("CASH_RECHARGE", "INC_RCHG_COMM", "5000.00", TODAY);

            PoolCloseResponse closed = service.closePool("POOL-1", count("4950.00"), null);

            assertEquals(new BigDecimal("-50.00"), closed.varianceAmount());
            assertNotNull(closed.varianceVoucherId());
            assertEquals("JOURNAL", voucherType(closed.varianceVoucherId()));
            assertEquals(new BigDecimal("50.00"), dr(closed.varianceVoucherId(), "EXP_CASH_SHORT"));
            assertEquals(new BigDecimal("50.00"), cr(closed.varianceVoucherId(), "CASH_RECHARGE"));

            // The journal moved the till, so the sweep moves the COUNTED figure.
            assertEquals(new BigDecimal("4950.00"), closed.sweep().amount());
        }

        @Test
        @DisplayName("⚠ there is no threshold — a ₹1 difference posts like any other")
        void noThreshold() {
            // log §9.1 is explicit and this is the test that stops a "small
            // enough to ignore" creeping in: EXP_CASH_SHORT and INC_CASH_OVER
            // answer "how much can we not account for?", and a threshold makes a
            // genuine unexplained amount invisible.
            postTakings("CASH_RECHARGE", "INC_RCHG_COMM", "100.00", TODAY);

            PoolCloseResponse closed = service.closePool("POOL-1", count("99.00"), null);

            assertNotNull(closed.varianceVoucherId(), "a ₹1 variance still posts");
            assertEquals(new BigDecimal("1.00"), dr(closed.varianceVoucherId(), "EXP_CASH_SHORT"));
        }

        @Test
        @DisplayName("E17c — over and unexplained: Dr the primary member / Cr INC_CASH_OVER")
        void unexplainedOver() {
            postTakings("CASH_RECHARGE", "INC_RCHG_COMM", "100.00", TODAY);

            PoolCloseResponse closed = service.closePool("POOL-1", count("150.00"), null);

            assertEquals(new BigDecimal("50.00"), closed.varianceAmount());
            assertEquals(new BigDecimal("50.00"), dr(closed.varianceVoucherId(), "CASH_RECHARGE"));
            assertEquals(new BigDecimal("50.00"), cr(closed.varianceVoucherId(), "INC_CASH_OVER"));
        }

        @Test
        @DisplayName("E17 — OUT, explained: a PAYMENT for the real expense, from CASH_SAFE")
        void explainedOut() {
            postTakings("CASH_RECHARGE", "INC_RCHG_COMM", "5000.00", TODAY);

            PoolCloseResponse closed = service.closePool("POOL-1",
                    new PoolCloseRequest(TODAY, new BigDecimal("4950.00"),
                            "₹50 short — milk for the shop",
                            new ExplainedVariance(VarianceDirection.OUT, "EXP_SUNDRY_SHOP",
                                    null, new BigDecimal("50.00"))),
                    null);

            // The real expense, not "Cash Short": booking it to the variance
            // account throws away the one fact worth keeping (log §9.1).
            assertEquals("PAYMENT", voucherType(closed.varianceVoucherId()));
            assertEquals(new BigDecimal("50.00"),
                    dr(closed.varianceVoucherId(), "EXP_SUNDRY_SHOP"));
            assertEquals(new BigDecimal("50.00"), cr(closed.varianceVoucherId(), "CASH_SAFE"));

            // ⚠ The till keeps its RECORDED balance, so the sweep moves 5000 and
            // the safe absorbs the ₹50 — ending at 4950, which is what is
            // physically there (E17's own note).
            assertEquals(new BigDecimal("5000.00"), closed.sweep().amount());
            assertEquals(new BigDecimal("4950.00"), balanceOf("CASH_SAFE"));
        }

        @Test
        @DisplayName("⚠ log §14.4 — IN, explained: a RECEIPT crediting INC_SUNDRY_SHOP (V34)")
        void explainedIn() {
            // The direction the original design could not express at all. Two
            // recurring cases arrive this way — someone paid for a photocopy
            // (1,179 baskets over seven years) and small change rounded away (₹1
            // occurs 12,026 times) — and until V34 there was no INCOME account
            // with ALLOW_MANUAL = TRUE for either to name.
            postTakings("CASH_RECHARGE", "INC_RCHG_COMM", "1000.00", TODAY);

            PoolCloseResponse closed = service.closePool("POOL-1",
                    new PoolCloseRequest(TODAY, new BigDecimal("1012.00"),
                            "photocopies and ₹2 rounding",
                            new ExplainedVariance(VarianceDirection.IN, "INC_SUNDRY_SHOP",
                                    null, new BigDecimal("12.00"))),
                    null);

            assertEquals("RECEIPT", voucherType(closed.varianceVoucherId()));
            assertEquals(new BigDecimal("12.00"), dr(closed.varianceVoucherId(), "CASH_SAFE"));
            assertEquals(new BigDecimal("12.00"),
                    cr(closed.varianceVoucherId(), "INC_SUNDRY_SHOP"));
            assertEquals(new BigDecimal("1012.00"), balanceOf("CASH_SAFE"));
        }

        @Test
        @DisplayName("the primary member absorbs the whole variance, never a non-primary one")
        void varianceLandsOnThePrimary() {
            postTakings("CASH_RECHARGE", "INC_RCHG_COMM", "2847.00", TODAY);
            postTakings("CASH_TICKET", "INC_TKT_COMM", "545.00", TODAY);

            service.closePool("POOL-1", count("3300.00"), null);

            // CASH_TICKET: counted == ledger, variance 0.00 by construction.
            assertEquals(new BigDecimal("0.00"), variance("CASH_TICKET"));
            // CASH_RECHARGE: 3300 - 545 = 2755 counted against 2847.
            assertEquals(new BigDecimal("2755.00"), counted("CASH_RECHARGE"));
            assertEquals(new BigDecimal("-92.00"), variance("CASH_RECHARGE"));
        }
    }

    // ==================================================================
    // §1.1's 422 list
    // ==================================================================

    @Nested
    @DisplayName("what the close refuses")
    class Refusals {

        @Test
        @DisplayName("⚠ poolCode = SAFE is refused with a 422 naming endpoint 4 (ADR 0002)")
        void safeIsNotAPool() {
            // V21:331 seeds CASH_SAFE into pool SAFE and the seed is what is
            // wrong. Closing it here would seal a Dr CASH_SAFE / Cr CASH_SAFE
            // contra EVERY NIGHT: it balances, passes every check, moves nothing,
            // and accumulates 365 times a year in the one account the owner reads
            // most. The seed stays wrong until S6, so this refusal is the only
            // thing standing between it and that.
            LedgerPostingException e = assertThrows(LedgerPostingException.class,
                    () -> service.closePool("SAFE", count("21795.00"), null));

            assertTrue(e.getMessage().contains("/accounts/CASH_SAFE/reconciliations"),
                    "the 422 has to say where to go instead; got: " + e.getMessage());
        }

        @Test
        @DisplayName("an unknown pool is refused rather than closing nothing silently")
        void unknownPool() {
            assertTrue(assertThrows(LedgerPostingException.class,
                    () -> service.closePool("POOL-9", count("0.00"), null))
                    .getMessage().contains("no such cash pool"));
        }

        @Test
        @DisplayName("⚠ reconDate in the future would seal vouchers nobody has counted")
        void futureReconDate() {
            LedgerPostingException e = assertThrows(LedgerPostingException.class,
                    () -> service.closePool("POOL-1",
                            new PoolCloseRequest(TODAY.plusDays(1), new BigDecimal("0.00"),
                                    null, null),
                            null));
            assertTrue(e.getMessage().contains("in the future"));
        }

        @Test
        @DisplayName("a negative count is refused")
        void negativeCount() {
            assertThrows(LedgerPostingException.class,
                    () -> service.closePool("POOL-1", count("-1.00"), null));
        }

        @Test
        @DisplayName("⚠ a member negative BEFORE the close is the fault, and is refused there")
        void negativeMemberBeforeTheClose() {
            // The counterpart to negativeMemberIsDebited. A till cannot hold less
            // than nothing, so a negative balance before any close voucher means
            // something posted a credit it had no cash for. After step 4 the same
            // sign is ordinary arithmetic — which is why the two are checked in
            // different places rather than once.
            post(new VoucherDraft(
                    "JOURNAL", TODAY, TODAY.atTime(9, 0), "a credit with no cash behind it",
                    "CORRECTION", null, null, null, null,
                    List.of(VoucherLeg.credit("CASH_RECHARGE", new BigDecimal("25.00")),
                            VoucherLeg.debit("EXP_SUNDRY_SHOP", new BigDecimal("25.00")))));

            assertTrue(assertThrows(LedgerPostingException.class,
                    () -> service.closePool("POOL-1", count("0.00"), null))
                    .getMessage().contains("cannot hold less than nothing"));
        }

        @Test
        @DisplayName("an explained block on a count that matches exactly")
        void explainedWithNoVariance() {
            postTakings("CASH_RECHARGE", "INC_RCHG_COMM", "100.00", TODAY);
            assertThrows(LedgerPostingException.class,
                    () -> service.closePool("POOL-1",
                            new PoolCloseRequest(TODAY, new BigDecimal("100.00"), null,
                                    new ExplainedVariance(VarianceDirection.OUT,
                                            "EXP_SUNDRY_SHOP", null, new BigDecimal("1.00"))),
                            null));
        }

        @Test
        @DisplayName("explained.amount short of the whole variance — refused, never split")
        void partialExplanation() {
            postTakings("CASH_RECHARGE", "INC_RCHG_COMM", "100.00", TODAY);
            LedgerPostingException e = assertThrows(LedgerPostingException.class,
                    () -> service.closePool("POOL-1",
                            new PoolCloseRequest(TODAY, new BigDecimal("50.00"), null,
                                    new ExplainedVariance(VarianceDirection.OUT,
                                            "EXP_SUNDRY_SHOP", null, new BigDecimal("20.00"))),
                            null));
            assertTrue(e.getMessage().contains("must equal the whole variance"));
        }

        @Test
        @DisplayName("OUT on an over — the direction has to agree with the sign")
        void directionDisagrees() {
            postTakings("CASH_RECHARGE", "INC_RCHG_COMM", "100.00", TODAY);
            assertTrue(assertThrows(LedgerPostingException.class,
                    () -> service.closePool("POOL-1",
                            new PoolCloseRequest(TODAY, new BigDecimal("150.00"), null,
                                    new ExplainedVariance(VarianceDirection.OUT,
                                            "EXP_SUNDRY_SHOP", null, new BigDecimal("50.00"))),
                            null))
                    .getMessage().contains("OVER"));
        }

        @Test
        @DisplayName("an EXPENSE account named for an IN variance")
        void wrongAccountTypeForDirection() {
            postTakings("CASH_RECHARGE", "INC_RCHG_COMM", "100.00", TODAY);
            assertTrue(assertThrows(LedgerPostingException.class,
                    () -> service.closePool("POOL-1",
                            new PoolCloseRequest(TODAY, new BigDecimal("150.00"), null,
                                    new ExplainedVariance(VarianceDirection.IN,
                                            "EXP_SUNDRY_SHOP", null, new BigDecimal("50.00"))),
                            null))
                    .getMessage().contains("needs an INCOME account"));
        }

        @Test
        @DisplayName("⚠ INC_CASH_OVER cannot be named by hand — it is the close's own account")
        void varianceAccountsAreNotNameable() {
            // ALLOW_MANUAL = FALSE on both (V21:304,310). They catch the
            // genuinely unexplained and nobody hand-posts them; naming one would
            // be a cause recorded as "no cause".
            postTakings("CASH_RECHARGE", "INC_RCHG_COMM", "100.00", TODAY);
            assertTrue(assertThrows(LedgerPostingException.class,
                    () -> service.closePool("POOL-1",
                            new PoolCloseRequest(TODAY, new BigDecimal("150.00"), null,
                                    new ExplainedVariance(VarianceDirection.IN,
                                            "INC_CASH_OVER", null, new BigDecimal("50.00"))),
                            null))
                    .getMessage().contains("ALLOW_MANUAL = FALSE"));
        }

        @Test
        @DisplayName("RATE_CORRECTION is refused on a pool — it is a float's correction")
        void rateCorrectionIsFloatOnly() {
            postTakings("CASH_RECHARGE", "INC_RCHG_COMM", "100.00", TODAY);
            assertTrue(assertThrows(LedgerPostingException.class,
                    () -> service.closePool("POOL-1",
                            new PoolCloseRequest(TODAY, new BigDecimal("150.00"), null,
                                    new ExplainedVariance(VarianceDirection.IN, null,
                                            VarianceReason.RATE_CORRECTION,
                                            new BigDecimal("50.00"))),
                            null))
                    .getMessage().contains("float account only"));
        }

        @Test
        @DisplayName("both an accountCode and a reason — exactly one, never both")
        void bothNamedAndReasoned() {
            postTakings("CASH_RECHARGE", "INC_RCHG_COMM", "100.00", TODAY);
            assertThrows(LedgerPostingException.class,
                    () -> service.closePool("POOL-1",
                            new PoolCloseRequest(TODAY, new BigDecimal("150.00"), null,
                                    new ExplainedVariance(VarianceDirection.IN,
                                            "INC_SUNDRY_SHOP", VarianceReason.RATE_CORRECTION,
                                            new BigDecimal("50.00"))),
                            null));
        }
    }

    // ==================================================================
    // Idempotency
    // ==================================================================

    @Nested
    @DisplayName("idempotency: uk_recon_account_date IS the key")
    class Idempotency {

        @Test
        @DisplayName("⚠ the derived keys land on acc_voucher, with :VARIANCE and :SWEEP")
        void derivedKeysAreStoredOnTheVouchers() {
            // uk_vch_idem is ONE unique column and #2 posts TWO vouchers under
            // one identity, so the stored values carry a suffix — the shape
            // TICKET_BOOKING:tkt_booking#<id>:CREATED already set (§1.3).
            postTakings("CASH_RECHARGE", "INC_RCHG_COMM", "100.00", TODAY);
            PoolCloseResponse closed = service.closePool("POOL-1", count("90.00"), null);

            assertEquals("POOL_CLOSE:POOL-1:" + TODAY + ":VARIANCE",
                    key(closed.varianceVoucherId()));
            assertEquals("POOL_CLOSE:POOL-1:" + TODAY + ":SWEEP",
                    key(closed.sweep().voucherId()));
        }

        @Test
        @DisplayName("a second close for the same date is refused by the unique key")
        void secondCloseIsRefused() {
            service.closePool("POOL-1", count("0.00"), null);

            // uk_recon_account_date IS the key — there is no application-side
            // bookkeeping to get round, and this closes the race between two
            // concurrent first-closes that a pre-check SELECT could not.
            assertThrows(DataIntegrityViolationException.class,
                    () -> service.closePool("POOL-1", count("0.00"), null));
        }

        @Test
        @DisplayName("⚠ the recovery returns the ORIGINAL close, sweep voucher and all")
        void replayReadsTheOriginal() {
            // This is what the controller's catch calls. It can only be tested
            // against COMMITTED rows: replayedPoolClose is REQUIRES_NEW exactly
            // so it can read what the aborted transaction cannot, so inside a
            // rolled-back test it would read an empty database and prove nothing.
            postTakings("CASH_RECHARGE", "INC_RCHG_COMM", "2847.00", TODAY);
            PoolCloseResponse first = service.closePool("POOL-1", count("2840.00"), null);

            assertThrows(DataIntegrityViolationException.class,
                    () -> service.closePool("POOL-1", count("2840.00"), null));

            PoolCloseResponse replayed =
                    service.replayedPoolClose("POOL-1", TODAY).orElseThrow();

            assertTrue(replayed.replayed(), "the client has to be able to tell");
            assertEquals(first.countedAmount(), replayed.countedAmount());
            assertEquals(first.expectedAmount(), replayed.expectedAmount());
            assertEquals(first.varianceAmount(), replayed.varianceAmount());
            assertEquals(first.varianceVoucherId(), replayed.varianceVoucherId());
            // ⚠ Rebuilt from acc_voucher.IDEMPOTENCY_KEY, which is why the keys
            // are stored there and not in a second mapping table.
            assertEquals(first.sweep().voucherId(), replayed.sweep().voucherId());
            assertEquals(first.sweep().amount(), replayed.sweep().amount());
            assertEquals(SweepStatus.POSTED, replayed.sweep().status());

            // And NOTHING was written twice: one sweep, one variance, two rows.
            assertEquals(2, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM acc_voucher WHERE IDEMPOTENCY_KEY LIKE 'POOL_CLOSE:%'",
                    Integer.class));
            assertEquals(2, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM acc_reconciliation", Integer.class));
        }

        @Test
        @DisplayName("a skipped sweep is reported as skipped on the replay too")
        void replayOfAZeroDay() {
            service.closePool("POOL-1", count("0.00"), null);

            PoolCloseResponse replayed =
                    service.replayedPoolClose("POOL-1", TODAY).orElseThrow();

            assertEquals(SweepStatus.SKIPPED_ALL_MEMBERS_ZERO, replayed.sweep().status());
            assertNull(replayed.sweep().voucherId());
        }

        @Test
        @DisplayName("a single account's replay returns its row")
        void replayOfAnAccountClose() {
            service.closeAccount("BANK_FATHER",
                    new ReconciliationRequest(TODAY, new BigDecimal("0.00"), null, null, null),
                    null);

            assertThrows(DataIntegrityViolationException.class,
                    () -> service.closeAccount("BANK_FATHER",
                            new ReconciliationRequest(TODAY, new BigDecimal("0.00"), null,
                                    null, null),
                            null));

            assertEquals("BANK_FATHER",
                    service.replayedAccountClose("BANK_FATHER", TODAY).orElseThrow()
                            .accountCode());
        }

        @Test
        @DisplayName("an unclosed day replays nothing")
        void nothingToReplay() {
            assertTrue(service.replayedPoolClose("POOL-1", TODAY).isEmpty());
            assertTrue(service.replayedAccountClose("CASH_SAFE", TODAY).isEmpty());
        }
    }

    // ==================================================================
    // Endpoint 4
    // ==================================================================

    @Nested
    @DisplayName("endpoint 4 — a single account")
    class SingleAccount {

        @Test
        @DisplayName("CASH_SAFE closes here, and nothing is swept")
        void theSafe() {
            postTakings("CASH_SAFE", "INC_RCHG_COMM", "21795.00", TODAY);

            ReconciliationResponse closed = service.closeAccount("CASH_SAFE",
                    new ReconciliationRequest(TODAY, new BigDecimal("21795.00"), null, null, null),
                    null);

            assertEquals(new BigDecimal("0.00"), closed.varianceAmount());
            assertNull(closed.varianceVoucherId());
            // No sweep: there is no account above the safe to sweep into.
            assertEquals(new BigDecimal("21795.00"), balanceOf("CASH_SAFE"));
        }

        @Test
        @DisplayName("a variance books against the account itself, not against CASH_SAFE")
        void varianceCounterpartIsTheAccount() {
            postTakings("BANK_JANATA", "INC_RCHG_COMM", "1000.00", TODAY);

            ReconciliationResponse closed = service.closeAccount("BANK_JANATA",
                    new ReconciliationRequest(TODAY, new BigDecimal("995.00"), null, null,
                            new ExplainedVariance(VarianceDirection.OUT, "EXP_BANK_CHARGES",
                                    null, new BigDecimal("5.00"))),
                    null);

            assertEquals(new BigDecimal("5.00"),
                    dr(closed.varianceVoucherId(), "EXP_BANK_CHARGES"));
            assertEquals(new BigDecimal("5.00"), cr(closed.varianceVoucherId(), "BANK_JANATA"));
            assertEquals(new BigDecimal("995.00"), balanceOf("BANK_JANATA"),
                    "nothing sweeps here, so a variance booked elsewhere would never reconcile");
        }

        @Test
        @DisplayName("⚠ a float's RATE_CORRECTION — Dr FLOAT_X / Cr INC_RCHG_COMM, from HERE")
        void floatRateCorrection() {
            // Never from endpoint 9 (log §14.9). A7 makes the float accounts
            // ALLOW_MANUAL = FALSE, so the generic journal cannot touch them —
            // and the correction is written in the SAME TRANSACTION as the
            // acc_reconciliation row, so a float balance can never move without
            // a record of the check that prompted it.
            postTakings("FLOAT_ARTL", "INC_RCHG_COMM", "4000.00", TODAY);

            ReconciliationResponse closed = service.closeAccount("FLOAT_ARTL",
                    new ReconciliationRequest(TODAY, new BigDecimal("4200.00"), null,
                            "portal read 4200 after the observed rate replaced the provisional",
                            new ExplainedVariance(VarianceDirection.IN, null,
                                    VarianceReason.RATE_CORRECTION, new BigDecimal("200.00"))),
                    null);

            assertEquals("JOURNAL", voucherType(closed.varianceVoucherId()));
            assertEquals(new BigDecimal("200.00"), dr(closed.varianceVoucherId(), "FLOAT_ARTL"));
            assertEquals(new BigDecimal("200.00"),
                    cr(closed.varianceVoucherId(), "INC_RCHG_COMM"),
                    "one journal repairs the commission understatement and the float drift "
                            + "together, because both errors are the same rupee amount");
        }

        @Test
        @DisplayName("⚠ RATE_CORRECTION is refused on a SHORT — its legs only raise the float")
        void rateCorrectionIsOverOnly() {
            // Dr FLOAT_X / Cr INC_RCHG_COMM raises the float, so it answers a
            // portal showing MORE than the books. Sent as OUT on a short it
            // passes the direction-agrees-with-sign check and would then move the
            // float further the wrong way, which is why it has its own refusal.
            postTakings("FLOAT_VI", "INC_RCHG_COMM", "4000.00", TODAY);

            assertTrue(assertThrows(LedgerPostingException.class,
                    () -> service.closeAccount("FLOAT_VI",
                            new ReconciliationRequest(TODAY, new BigDecimal("3800.00"), null, null,
                                    new ExplainedVariance(VarianceDirection.OUT, null,
                                            VarianceReason.RATE_CORRECTION,
                                            new BigDecimal("200.00"))),
                            null))
                    .getMessage().contains("direction IN) only"));
        }

        @Test
        @DisplayName("faceCounted defaults to countedAmount, and FACE_LEDGER is derived")
        void floatFaceFigures() {
            ReconciliationResponse closed = service.closeAccount("FLOAT_RJIO",
                    new ReconciliationRequest(TODAY, new BigDecimal("0.00"), null, null, null),
                    null);

            // Ratio 1.0 on all four wallets (log §2.4:97-99), so the one figure
            // read off the portal is both sides.
            assertEquals(new BigDecimal("0.00"), closed.faceCounted());
            assertEquals(new BigDecimal("0.00"), closed.faceLedger(),
                    "SUM(acc_float_movement.FACE_DELTA), and no movements exist yet");
        }

        @Test
        @DisplayName("faceCounted is refused on an account that is not a float")
        void faceOnANonFloat() {
            assertTrue(assertThrows(LedgerPostingException.class,
                    () -> service.closeAccount("CASH_SAFE",
                            new ReconciliationRequest(TODAY, new BigDecimal("0.00"),
                                    new BigDecimal("1.00"), null, null),
                            null))
                    .getMessage().contains("float account only"));
        }

        @Test
        @DisplayName("⚠ a pooled account is refused here, naming endpoint 2")
        void aPooledAccountClosesWithItsPool() {
            LedgerPostingException e = assertThrows(LedgerPostingException.class,
                    () -> service.closeAccount("CASH_TICKET",
                            new ReconciliationRequest(TODAY, new BigDecimal("0.00"), null,
                                    null, null),
                            null));
            assertTrue(e.getMessage().contains("/accounts/pools/POOL-1/closes"),
                    "a pool closes as a unit, from one counted figure; got: " + e.getMessage());
        }

        @Test
        @DisplayName("the derived key is the bare ACCT_RECON form — one voucher, no suffix")
        void bareKey() {
            postTakings("BANK_SELF", "INC_RCHG_COMM", "100.00", TODAY);
            ReconciliationResponse closed = service.closeAccount("BANK_SELF",
                    new ReconciliationRequest(TODAY, new BigDecimal("99.00"), null, null, null),
                    null);

            assertEquals("ACCT_RECON:BANK_SELF:" + TODAY, key(closed.varianceVoucherId()));
        }

        @Test
        @DisplayName("an account with no independent source cannot be reconciled")
        void notReconcilable() {
            assertTrue(assertThrows(LedgerPostingException.class,
                    () -> service.closeAccount("EXP_SUNDRY_SHOP",
                            new ReconciliationRequest(TODAY, new BigDecimal("0.00"), null,
                                    null, null),
                            null))
                    .getMessage().contains("IS_RECONCILABLE"));
        }
    }

    // ==================================================================
    // Endpoints 1 and 3
    // ==================================================================

    @Nested
    @DisplayName("endpoint 1 — the preview")
    class Preview {

        @Test
        @DisplayName("every pool with its members, and CASH_SAFE under accounts not pools")
        void shape() {
            postTakings("CASH_RECHARGE", "INC_RCHG_COMM", "2847.00", TODAY);
            postTakings("CASH_TICKET", "INC_TKT_COMM", "545.00", TODAY);

            ClosePreviewResponse preview = service.preview();

            assertEquals(List.of("POOL-1", "POOL-2"),
                    preview.pools().stream()
                            .map(ClosePreviewResponse.PoolPreview::poolCode).sorted().toList(),
                    "SAFE is not a pool here, however V21:331 seeds it (ADR 0002)");
            assertTrue(preview.accounts().stream()
                            .anyMatch(a -> a.accountCode().equals("CASH_SAFE")),
                    "artboard 9's 'After closing — Safe' needs it, and endpoint 14 is not in "
                            + "this slice");
            assertFalse(preview.accounts().stream()
                            .anyMatch(a -> a.accountCode().equals("CASH_TICKET")),
                    "a pool member must not also be offered as a single close");
        }

        @Test
        @DisplayName("expectedAmount is the sum of DERIVED balances, never of recorded ones")
        void expectedAmountIsDerived() {
            postTakings("CASH_RECHARGE", "INC_RCHG_COMM", "2847.00", TODAY);
            postTakings("CASH_TICKET", "INC_TKT_COMM", "545.00", TODAY);

            ClosePreviewResponse.PoolPreview pool = poolOf(service.preview(), "POOL-1");

            assertEquals(new BigDecimal("3392.00"), pool.expectedAmount());
            assertTrue(pool.members().stream()
                    .anyMatch(m -> m.isPoolPrimary()
                            && m.accountCode().equals("CASH_RECHARGE")));
        }

        @Test
        @DisplayName("⚠ POOL-2 reports closeable:false while crockery is unrecorded")
        void poolTwoCannotBalanceYet() {
            // The server states the fact and the screen owns the wording (§1.1).
            // Crockery cash is already in the water drawer and no crockery sale
            // is posted anywhere (log §3, V21:287-289), so the count will always
            // exceed the recorded balance.
            ClosePreviewResponse.PoolPreview pool = poolOf(service.preview(), "POOL-2");

            assertFalse(pool.closeable());
            assertEquals("CROCKERY_NOT_RECORDED", pool.reason());
            assertTrue(poolOf(service.preview(), "POOL-1").closeable());
            assertNull(poolOf(service.preview(), "POOL-1").reason());
        }

        @Test
        @DisplayName("⚠ the endpoint stays generic: POOL-2 still closes if asked")
        void previewAdvisesAndDoesNotEnforce() {
            // closeable:false is the SCREEN's input, not a rule. The close
            // endpoint is generic and the screen is what declines to offer it.
            PoolCloseResponse closed = service.closePool("POOL-2", count("0.00"), null);
            assertEquals(TODAY, closed.reconDate());
        }

        @Test
        @DisplayName("both date fields come back, and off the same row")
        void bothDateFields() {
            ClosePreviewResponse.PoolPreview before = poolOf(service.preview(), "POOL-1");
            assertNull(before.lastClosedAt(), "never closed reads as null, not as a zero date");
            assertNull(before.lastReconDate());

            service.closePool("POOL-1", count("0.00"), null);

            ClosePreviewResponse.PoolPreview after = poolOf(service.preview(), "POOL-1");
            assertEquals(TODAY, after.lastReconDate(), "which business day was sealed");
            assertEquals(LocalDateTime.of(TODAY, java.time.LocalTime.of(20, 40)),
                    after.lastClosedAt(), "when the drawer was physically emptied");
        }
    }

    @Nested
    @DisplayName("endpoint 3 — the history")
    class History {

        @Test
        @DisplayName("one account's closes, newest first")
        void newestFirst() {
            service.closeAccount("BANK_WIFE",
                    new ReconciliationRequest(TODAY.minusDays(2), new BigDecimal("0.00"),
                            null, null, null), null);
            service.closeAccount("BANK_WIFE",
                    new ReconciliationRequest(TODAY, new BigDecimal("0.00"), null, null, null),
                    null);

            List<ReconciliationResponse> rows =
                    service.history("BANK_WIFE", null, null, 0, 20).getContent();

            assertEquals(2, rows.size());
            assertEquals(TODAY, rows.get(0).reconDate());
            assertEquals(TODAY.minusDays(2), rows.get(1).reconDate());
        }

        @Test
        @DisplayName("?from=&to= narrows it, both inclusive")
        void dateRange() {
            service.closeAccount("BANK_WIFE",
                    new ReconciliationRequest(TODAY.minusDays(5), new BigDecimal("0.00"),
                            null, null, null), null);
            service.closeAccount("BANK_WIFE",
                    new ReconciliationRequest(TODAY, new BigDecimal("0.00"), null, null, null),
                    null);

            assertEquals(1, service.history("BANK_WIFE", TODAY, TODAY, 0, 20).getTotalElements());
            assertEquals(2, service.history("BANK_WIFE", TODAY.minusDays(5), TODAY, 0, 20)
                    .getTotalElements());
        }

        @Test
        @DisplayName("an unknown account is refused rather than returning an empty page")
        void unknownAccount() {
            assertTrue(assertThrows(LedgerPostingException.class,
                    () -> service.history("BANK_NOPE", null, null, 0, 20))
                    .getMessage().contains("no such account"));
        }
    }

    // ---------------------------------------------------------------- helpers

    private static ClosePreviewResponse.PoolPreview poolOf(ClosePreviewResponse preview,
                                                           String poolCode) {
        return preview.pools().stream().filter(p -> p.poolCode().equals(poolCode))
                .findFirst().orElseThrow();
    }

    private String voucherType(Long voucherId) {
        return jdbc.queryForObject("SELECT VOUCHER_TYPE FROM acc_voucher WHERE VOUCHER_ID = ?",
                String.class, voucherId);
    }

    private String key(Long voucherId) {
        return jdbc.queryForObject("SELECT IDEMPOTENCY_KEY FROM acc_voucher WHERE VOUCHER_ID = ?",
                String.class, voucherId);
    }

    private BigDecimal dr(Long voucherId, String accountCode) {
        return jdbc.queryForObject("SELECT DR_AMOUNT FROM acc_voucher_line "
                + "WHERE VOUCHER_ID = ? AND ACCOUNT_CODE = ?",
                BigDecimal.class, voucherId, accountCode);
    }

    private BigDecimal cr(Long voucherId, String accountCode) {
        return jdbc.queryForObject("SELECT CR_AMOUNT FROM acc_voucher_line "
                + "WHERE VOUCHER_ID = ? AND ACCOUNT_CODE = ?",
                BigDecimal.class, voucherId, accountCode);
    }

    private BigDecimal variance(String accountCode) {
        return jdbc.queryForObject("SELECT VARIANCE_AMOUNT FROM acc_reconciliation "
                + "WHERE ACCOUNT_CODE = ? AND RECON_DATE = ?",
                BigDecimal.class, accountCode, TODAY);
    }

    private BigDecimal counted(String accountCode) {
        return jdbc.queryForObject("SELECT COUNTED_AMOUNT FROM acc_reconciliation "
                + "WHERE ACCOUNT_CODE = ? AND RECON_DATE = ?",
                BigDecimal.class, accountCode, TODAY);
    }
}
