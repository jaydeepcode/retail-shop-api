package com.mangle.retailshopapp.accounting;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.mangle.retailshopapp.accounting.model.request.UpiSettlementRequest;
import com.mangle.retailshopapp.accounting.model.response.UpiPendingResponse;
import com.mangle.retailshopapp.accounting.model.response.UpiSettlementResponse;
import com.mangle.retailshopapp.settings.SettingService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UPI settlement — endpoints 5 and 6 against real MySQL.
 *
 * <p>⚠ <b>THE PARTIAL SETTLEMENT IS THE POINT OF THIS FILE.</b> The brief is
 * explicit about why: "a settlement has never once mismatched in seven years
 * (log §9.2), so the partial case is rare, which is exactly why it will not be
 * exercised by hand". The {@code :<n>} sequence exists only for that case, and a
 * key of one-per-date would reject the second call — so if it is wrong, nothing
 * short of a real mismatch in production would find it, and by then the money is
 * already missing from the books.
 *
 * <p>The container is required for three reasons beyond the usual:
 * {@code uk_recon_account_date} is what makes the row one-per-date,
 * {@code VARIANCE_AMOUNT} is a generated STORED column that only MySQL computes —
 * and for a UPI row it is the <b>negated residue</b> rather than a loss — and
 * {@code rc_setting['bank.default.account']} is a V9 seed, so resolving the bank
 * server-side (log §9.3) cannot be tested without the migrations.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({UpiSettlementService.class, LedgerPostingService.class, SettingService.class,
        UpiSettlementServiceTest.FixedClock.class})
@DisplayName("UPI settlement, against MySQL")
class UpiSettlementServiceTest {

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(LocalDate.now().atTime(10, 5).atZone(ZoneId.systemDefault())
                    .toInstant(), ZoneId.systemDefault());
        }
    }

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
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.MySQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("spring.flyway.baseline-version", () -> "8");
        registry.add("spring.flyway.target", () -> "34");
        registry.add("spring.flyway.out-of-order", () -> "false");
    }

    @Autowired
    private UpiSettlementService service;

    @Autowired
    private LedgerPostingService postings;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private JdbcTemplate jdbc;

    private static final LocalDate TODAY = LocalDate.now();
    private static final LocalDate SEP_30 = TODAY.minusDays(10);
    private static final LocalDate OCT_1 = TODAY.minusDays(9);

    /** See ManualVoucherServiceTest: the staff id would fail fk_vch_creby. */
    private static final Integer NO_STAFF = null;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM acc_reconciliation");
        jdbc.update("DELETE FROM acc_voucher_line");
        jdbc.update("DELETE FROM acc_voucher WHERE REVERSES_VOUCHER_ID IS NOT NULL");
        jdbc.update("DELETE FROM acc_voucher");
    }

    // ==================================================================
    // Endpoint 5 — the ageing
    // ==================================================================

    @Nested
    @DisplayName("#5 GET /accounts/upi-clearing/pending")
    class Pending {

        @Test
        @DisplayName("groups by collection date, ages against asOf, oldest first")
        void groupsAndAges() {
            collectUpi(SEP_30, "3450.00");
            collectUpi(OCT_1, "1200.00");

            UpiPendingResponse pending = service.pending(TODAY);

            assertEquals(TODAY, pending.asOf());
            assertEquals(2, pending.dates().size());
            assertEquals(SEP_30, pending.dates().get(0).collectionDate(), "oldest first");
            assertEquals(new BigDecimal("3450.00"), pending.dates().get(0).collectedAmount());
            assertEquals(new BigDecimal("0.00"), pending.dates().get(0).settledAmount());
            assertEquals(10, pending.dates().get(0).ageDays());
            assertEquals(9, pending.dates().get(1).ageDays());
            assertEquals(new BigDecimal("4650.00"), pending.totalPending());
        }

        @Test
        @DisplayName("⚠ the total equals UPI_CLEARING's own balance — nothing settled elsewhere")
        void totalMatchesTheAccountBalance() {
            // The invariant that says every settlement went through endpoint 6.
            // If some other path ever credits UPI_CLEARING, these two part
            // company and this is what notices.
            collectUpi(SEP_30, "3450.00");
            collectUpi(OCT_1, "1200.00");
            service.settle(new UpiSettlementRequest(SEP_30, OCT_1, new BigDecimal("3200.00"),
                    "250 short"), "key-partial-1", NO_STAFF);

            assertEquals(balanceOf("UPI_CLEARING"), service.pending(TODAY).totalPending());
        }

        @Test
        @DisplayName("a fully settled date drops off, because it is not in transit")
        void settledDatesDropOff() {
            collectUpi(SEP_30, "3450.00");
            service.settle(new UpiSettlementRequest(SEP_30, OCT_1, new BigDecimal("3450.00"),
                    null), "key-full-1", NO_STAFF);

            assertTrue(service.pending(TODAY).dates().isEmpty());
            assertEquals(new BigDecimal("0.00"), service.pending(TODAY).totalPending());
        }

        @Test
        @DisplayName("asOf excludes later collections rather than ageing them negatively")
        void asOfBounds() {
            collectUpi(SEP_30, "3450.00");
            collectUpi(TODAY, "500.00");

            UpiPendingResponse asOfSep30 = service.pending(SEP_30);
            assertEquals(1, asOfSep30.dates().size());
            assertEquals(0, asOfSep30.dates().get(0).ageDays(), "0 on the day itself");
            assertEquals(new BigDecimal("3450.00"), asOfSep30.totalPending());
        }

        @Test
        @DisplayName("⚠ a later correcting credit shows as a negative row, not as nothing")
        void negativeRowsAreShown() {
            // A correcting JOURNAL is dated the day the mistake was FOUND
            // (FRD §8.3), so it reduces THAT day — which can take a day below
            // what was settled against it. Filtering negatives out is how a
            // reconciliation silently stops adding up.
            collectUpi(SEP_30, "3450.00");
            post("JOURNAL", TODAY, List.of(
                    VoucherLeg.debit("EXP_RCHG_LOSS", new BigDecimal("50.00")),
                    VoucherLeg.credit("UPI_CLEARING", new BigDecimal("50.00"))));

            UpiPendingResponse pending = service.pending(TODAY);
            assertEquals(2, pending.dates().size());
            assertEquals(new BigDecimal("-50.00"),
                    pending.dates().get(1).pendingAmount());
            assertEquals(new BigDecimal("3400.00"), pending.totalPending());
        }
    }

    // ==================================================================
    // Endpoint 6 — recording what the bank credited
    // ==================================================================

    @Nested
    @DisplayName("#6 POST /accounts/upi-clearing/settlements")
    class Settling {

        @Test
        @DisplayName("E16: Dr BANK_JANATA / Cr UPI_CLEARING, with the bank from rc_setting")
        void postsTheFullSettlement() {
            collectUpi(SEP_30, "3450.00");

            UpiSettlementResponse settled = service.settle(
                    new UpiSettlementRequest(SEP_30, OCT_1, new BigDecimal("3450.00"), null),
                    "key-e16", NO_STAFF);

            // log §9.3: resolved server-side, never a constant and never sent.
            assertEquals("BANK_JANATA", settled.bankAccountCode());
            assertEquals("BANK_JANATA", jdbc.queryForObject(
                    "SELECT SETTING_VALUE FROM rc_setting "
                            + "WHERE SETTING_KEY = 'bank.default.account'", String.class));
            assertEquals(new BigDecimal("3450.00"), balanceOf("BANK_JANATA"));
            assertEquals(new BigDecimal("0.00"), balanceOf("UPI_CLEARING"));
            assertEquals(new BigDecimal("0.00"), settled.residualAmount());
            assertFalse(settled.replayed());

            // The voucher is a CONTRA dated when the money LANDED, with the
            // sequence published on SOURCE_REF.
            assertEquals("CONTRA", jdbc.queryForObject(
                    "SELECT VOUCHER_TYPE FROM acc_voucher WHERE VOUCHER_ID = ?",
                    String.class, settled.voucherId()));
            assertEquals(OCT_1, jdbc.queryForObject(
                    "SELECT VOUCHER_DATE FROM acc_voucher WHERE VOUCHER_ID = ?",
                    LocalDate.class, settled.voucherId()));
            assertEquals("UPI_SETTLEMENT:" + SEP_30 + ":1", settled.sequence());
            assertEquals(settled.sequence(), jdbc.queryForObject(
                    "SELECT SOURCE_REF FROM acc_voucher WHERE VOUCHER_ID = ?",
                    String.class, settled.voucherId()));

            // ⚠ The CLIENT's key is what uk_vch_idem holds — the correction of
            // §1.3 taken with the owner on 2026-11-16. The sequence is published,
            // not the guard.
            assertEquals("key-e16", jdbc.queryForObject(
                    "SELECT IDEMPOTENCY_KEY FROM acc_voucher WHERE VOUCHER_ID = ?",
                    String.class, settled.voucherId()));
        }

        @Test
        @DisplayName("the reconciliation row is the evidence, dated the COLLECTION day")
        void writesTheReconciliationRow() {
            collectUpi(SEP_30, "3450.00");
            UpiSettlementResponse settled = service.settle(
                    new UpiSettlementRequest(SEP_30, OCT_1, new BigDecimal("3450.00"),
                            "all of it"), "key-row", NO_STAFF);

            assertEquals(SEP_30, settled.reconciliation().reconDate(),
                    "§2: the row is the evidence for the voucher's amount");
            assertEquals(new BigDecimal("3450.00"), settled.reconciliation().countedAmount());
            assertEquals(new BigDecimal("3450.00"), settled.reconciliation().ledgerAmount());
            assertEquals(new BigDecimal("0.00"), settled.reconciliation().varianceAmount());
            // ⚠ No variance voucher, ever: the residue is money in transit and
            // nothing books it (§1.2).
            assertEquals(null, settled.reconciliation().varianceVoucherId());
        }

        @Test
        @DisplayName("⚠ E16b: a PARTIAL settlement, then the rest — the :n sequence at work")
        void thePartialSettlement() {
            collectUpi(SEP_30, "3450.00");

            UpiSettlementResponse first = service.settle(
                    new UpiSettlementRequest(SEP_30, OCT_1, new BigDecimal("3200.00"),
                            "250 short, not yet arrived"), "key-part-1", NO_STAFF);

            assertEquals("UPI_SETTLEMENT:" + SEP_30 + ":1", first.sequence());
            assertEquals(new BigDecimal("250.00"), first.residualAmount());
            // The row records what has arrived against what was collected, so the
            // generated VARIANCE_AMOUNT is the NEGATED residue — and is not a loss.
            assertEquals(new BigDecimal("-250.00"), first.reconciliation().varianceAmount());
            assertEquals(new BigDecimal("250.00"), balanceOf("UPI_CLEARING"),
                    "the remainder stays in transit; nothing is written off");

            // Three days later the rest arrives. A key of one-per-date would
            // reject this call, which is the whole reason for the sequence.
            UpiSettlementResponse second = service.settle(
                    new UpiSettlementRequest(SEP_30, TODAY, new BigDecimal("250.00"),
                            "the 250 arrived"), "key-part-2", NO_STAFF);

            assertEquals("UPI_SETTLEMENT:" + SEP_30 + ":2", second.sequence());
            assertEquals(new BigDecimal("3450.00"), second.settledToDate());
            assertEquals(new BigDecimal("0.00"), second.residualAmount());
            assertEquals(new BigDecimal("0.00"), second.reconciliation().varianceAmount());
            assertEquals(new BigDecimal("0.00"), balanceOf("UPI_CLEARING"));
            assertEquals(new BigDecimal("3450.00"), balanceOf("BANK_JANATA"));

            // TWO vouchers and ONE row: uk_recon_account_date allows one row per
            // date, so the second settlement extends it (A4).
            assertEquals(2, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM acc_voucher WHERE SOURCE_TYPE = 'UPI_SETTLEMENT'",
                    Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM acc_reconciliation WHERE ACCOUNT_CODE = 'UPI_CLEARING'",
                    Integer.class));
            assertTrue(service.pending(TODAY).dates().isEmpty());
        }

        @Test
        @DisplayName("⚠ a replay writes nothing, and does not add to the row a second time")
        void replayDoesNotDoubleTheRow() {
            // The hole in §1.3's server-derived key: a retry would have derived
            // :2 and recorded the 3,200 twice. With the client's key it is a 409
            // carrying the original, and — the part a voucher-only check would
            // miss — COUNTED_AMOUNT is not incremented again either, because the
            // service looks the key up before it touches the row.
            collectUpi(SEP_30, "3450.00");
            UpiSettlementRequest request = new UpiSettlementRequest(
                    SEP_30, OCT_1, new BigDecimal("3200.00"), "250 short");

            UpiSettlementResponse first = service.settle(request, "key-retry", NO_STAFF);
            UpiSettlementResponse replay = service.settle(request, "key-retry", NO_STAFF);

            assertTrue(replay.replayed());
            assertEquals(first.voucherId(), replay.voucherId());
            assertEquals("UPI_SETTLEMENT:" + SEP_30 + ":1", replay.sequence());
            assertEquals(new BigDecimal("3200.00"), replay.settledToDate());
            assertEquals(new BigDecimal("250.00"), replay.residualAmount());
            assertEquals("BANK_JANATA", replay.bankAccountCode());

            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM acc_voucher WHERE SOURCE_TYPE = 'UPI_SETTLEMENT'",
                    Integer.class));
            assertEquals(new BigDecimal("3200.00"), jdbc.queryForObject(
                    "SELECT COUNTED_AMOUNT FROM acc_reconciliation "
                            + "WHERE ACCOUNT_CODE = 'UPI_CLEARING'", BigDecimal.class));
            assertEquals(new BigDecimal("3200.00"), balanceOf("BANK_JANATA"));
        }

        @Test
        @DisplayName("⚠ 422 on more than is pending, naming both figures")
        void cannotSettleMoreThanIsPending() {
            collectUpi(SEP_30, "3450.00");
            service.settle(new UpiSettlementRequest(SEP_30, OCT_1, new BigDecimal("3200.00"),
                    null), "key-over-1", NO_STAFF);

            LedgerPostingException refused = assertThrows(LedgerPostingException.class,
                    () -> service.settle(new UpiSettlementRequest(SEP_30, TODAY,
                            new BigDecimal("300.00"), null), "key-over-2", NO_STAFF));
            assertTrue(refused.getMessage().contains("more than the 250.00 still pending"),
                    refused.getMessage());
            assertTrue(refused.getMessage().contains("needs a journal"), refused.getMessage());
            // And nothing was written: the row still says 3,200.
            assertEquals(new BigDecimal("3200.00"), jdbc.queryForObject(
                    "SELECT COUNTED_AMOUNT FROM acc_reconciliation "
                            + "WHERE ACCOUNT_CODE = 'UPI_CLEARING'", BigDecimal.class));
        }

        @Test
        @DisplayName("422 on money landing before it was taken, and on a non-positive amount")
        void theDateAndAmountRefusals() {
            collectUpi(SEP_30, "3450.00");

            assertTrue(assertThrows(LedgerPostingException.class, () -> service.settle(
                    new UpiSettlementRequest(SEP_30, SEP_30.minusDays(1),
                            new BigDecimal("100.00"), null), "key-backwards", NO_STAFF))
                    .getMessage().contains("Money cannot land before it was taken"));

            assertTrue(assertThrows(LedgerPostingException.class, () -> service.settle(
                    new UpiSettlementRequest(SEP_30, OCT_1, new BigDecimal("0.00"), null),
                    "key-zero-settle", NO_STAFF))
                    .getMessage().contains("settledAmount must be positive"));

            assertTrue(assertThrows(LedgerPostingException.class, () -> service.settle(
                    new UpiSettlementRequest(SEP_30, TODAY.plusDays(1),
                            new BigDecimal("100.00"), null), "key-future", NO_STAFF))
                    .getMessage().contains("is in the future"));
        }

        @Test
        @DisplayName("⚠ 422 when that date collected nothing, pointing at the likely mistake")
        void nothingToSettleAgainst() {
            collectUpi(SEP_30, "3450.00");
            // Settling against the day it LANDED rather than the day it was
            // taken is the obvious thing to get wrong, and the message says so.
            LedgerPostingException refused = assertThrows(LedgerPostingException.class,
                    () -> service.settle(new UpiSettlementRequest(OCT_1, OCT_1,
                            new BigDecimal("3450.00"), null), "key-wrong-date", NO_STAFF));
            assertTrue(refused.getMessage().contains("nothing was collected by UPI on " + OCT_1),
                    refused.getMessage());
            assertTrue(refused.getMessage().contains("not the day it landed"),
                    refused.getMessage());
        }

        @Test
        @DisplayName("⚠ 422 when a close already reconciled that date, naming endpoint 4")
        void refusesToAccumulateOntoACloseRow() {
            // UPI_CLEARING is IS_RECONCILABLE = TRUE and in no pool, so
            // endpoint 4 can close it for any date and write exactly this row.
            // Adding to it would leave one row claiming to be two kinds of count.
            collectUpi(SEP_30, "3450.00");
            jdbc.update("INSERT INTO acc_reconciliation (ACCOUNT_CODE, RECON_DATE, "
                    + "COUNTED_AMOUNT, LEDGER_AMOUNT, CLOSED_AT) "
                    + "VALUES ('UPI_CLEARING', ?, 1000.00, 3450.00, NOW())", SEP_30);

            LedgerPostingException refused = assertThrows(LedgerPostingException.class,
                    () -> service.settle(new UpiSettlementRequest(SEP_30, OCT_1,
                            new BigDecimal("100.00"), null), "key-foreign-row", NO_STAFF));
            assertTrue(refused.getMessage().contains("written by something other than a "
                    + "settlement"), refused.getMessage());
            assertTrue(refused.getMessage().contains(
                    "/accounts/UPI_CLEARING/reconciliations"), refused.getMessage());
        }

        @Test
        @DisplayName("⚠ 422 when a close BOOKED a variance for that date, even at equal figures")
        void refusesToExtendACloseThatBookedAVariance() {
            // Found by running it. The amount check alone cannot see this: a
            // close of UPI_CLEARING on a day with no collections writes
            // COUNTED_AMOUNT 0.00, which is exactly what "no settlements yet"
            // looks like — so the row would be extended and the close's evidence
            // overwritten. VARIANCE_VOUCHER_ID is the exact discriminator,
            // because a settlement never sets one.
            collectUpi(SEP_30, "3450.00");
            // ⚠ A PARTIAL write-off (1,000 of 3,450), so 2,450 is still shown as
            // collected and the discriminator below is what refuses the
            // settlement. A write-off for the WHOLE amount nets that collection
            // date to zero and the earlier "nothing was collected on …" refusal
            // fires first instead — correct, and not what this test is about.
            long varianceVoucher = tx.execute(status -> postings.post(new VoucherDraft(
                    "JOURNAL", SEP_30, SEP_30.atTime(20, 0), "a close booked this",
                    "CASH_RECON", null, null, null, NO_STAFF,
                    List.of(VoucherLeg.debit("EXP_RCHG_LOSS", new BigDecimal("1000.00")),
                            VoucherLeg.credit("UPI_CLEARING", new BigDecimal("1000.00")))))
                    .voucherId());
            jdbc.update("INSERT INTO acc_reconciliation (ACCOUNT_CODE, RECON_DATE, "
                    + "COUNTED_AMOUNT, LEDGER_AMOUNT, VARIANCE_VOUCHER_ID, CLOSED_AT) "
                    + "VALUES ('UPI_CLEARING', ?, 0.00, 0.00, ?, NOW())",
                    SEP_30, varianceVoucher);

            LedgerPostingException refused = assertThrows(LedgerPostingException.class,
                    () -> service.settle(new UpiSettlementRequest(SEP_30, OCT_1,
                            new BigDecimal("100.00"), null), "key-close-variance", NO_STAFF));
            assertTrue(refused.getMessage().contains("written by a close (endpoint 4)"),
                    refused.getMessage());
            assertTrue(refused.getMessage().contains("a settlement never books one"),
                    refused.getMessage());
        }

        @Test
        @DisplayName("⚠ a missing bank setting is a 500, not a guess")
        void aMissingBankSettingFailsLoudly() {
            // log §9.3: this key has no fallback ON PURPOSE. The request is not
            // wrong, so a 422 would be a lie; the server is misconfigured and
            // IllegalStateException is how SettingService says so.
            collectUpi(SEP_30, "100.00");
            jdbc.update("DELETE FROM rc_setting WHERE SETTING_KEY = 'bank.default.account'");
            try {
                IllegalStateException failed = assertThrows(IllegalStateException.class,
                        () -> service.settle(new UpiSettlementRequest(SEP_30, OCT_1,
                                new BigDecimal("100.00"), null), "key-no-bank", NO_STAFF));
                assertTrue(failed.getMessage().contains("bank.default.account"),
                        failed.getMessage());
                assertEquals(0, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM acc_reconciliation", Integer.class),
                        "and nothing was written on the way to failing");
            } finally {
                jdbc.update("INSERT INTO rc_setting (SETTING_KEY, SETTING_VALUE, VALUE_TYPE, "
                        + "DESCRIPTION) VALUES ('bank.default.account', 'BANK_JANATA', "
                        + "'STRING', 'restored by UpiSettlementServiceTest')");
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A UPI sale: {@code Dr UPI_CLEARING / Cr INC_WATER_TRIP}, posted through the
     * real service so the balances the endpoints read are ones V26's view derived
     * rather than rows poked in behind it.
     */
    private void collectUpi(LocalDate on, String amount) {
        post("SALES", on, List.of(
                VoucherLeg.debit("UPI_CLEARING", new BigDecimal(amount))
                        .withPaymentMethod("UPI"),
                VoucherLeg.credit("INC_WATER_TRIP", new BigDecimal(amount))));
    }

    /**
     * Wrapped in a {@code TransactionTemplate} because
     * {@link LedgerPostingService#post} is {@code MANDATORY} — §5.1 calls that
     * "the single most valuable line in this design" and it is not relaxed for a
     * test's convenience.
     */
    private void post(String type, LocalDate on, List<VoucherLeg> legs) {
        tx.executeWithoutResult(status -> postings.post(new VoucherDraft(
                type, on, on.atTime(12, 0), "test fixture", "WATER_TRIP", null,
                null, null, NO_STAFF, legs)));
    }

    private BigDecimal balanceOf(String code) {
        return jdbc.queryForObject(
                "SELECT BALANCE FROM acc_account_balance WHERE ACCOUNT_CODE = ?",
                BigDecimal.class, code);
    }
}
