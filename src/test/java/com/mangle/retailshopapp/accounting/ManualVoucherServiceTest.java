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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.mangle.retailshopapp.accounting.model.request.ContraRequest;
import com.mangle.retailshopapp.accounting.model.request.JournalRequest;
import com.mangle.retailshopapp.accounting.model.request.JournalRequest.JournalLeg;
import com.mangle.retailshopapp.accounting.model.request.PaymentRequest;
import com.mangle.retailshopapp.accounting.model.request.PurchaseRequest;
import com.mangle.retailshopapp.accounting.model.response.AccountResponse;
import com.mangle.retailshopapp.accounting.model.response.VoucherResponse;
import com.mangle.retailshopapp.settings.SettingService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Endpoints 7, 8, 9 and 19 against real MySQL, plus the picker list (#12).
 *
 * <p>⚠ THE CONTAINER IS NOT OPTIONAL. Four of the behaviours below exist only in
 * a real database: {@code ck_line_nonzero CHECK (DR_AMOUNT + CR_AMOUNT > 0)} is
 * what makes a zero fee leg a FAILED top-up rather than a junk line,
 * {@code uk_vch_idem} is the replay guard, {@code uk_fbatch_voucher} is what stops
 * a second batch for one top-up, and {@code ck_fmov_topup} requires a
 * {@code BATCH_ID} on a {@code TOPUP}. H2 enforces none of them, and the chart of
 * accounts these rules are written against is a V21 seed.
 *
 * <p>Flyway runs to <b>34</b>: {@code INC_SUNDRY_SHOP} is the only
 * {@code INCOME} account with {@code ALLOW_MANUAL = TRUE}, so without V34 the
 * journal tests have no legal income account to name at all.
 *
 * <p>⚠ <b>#12 IS A {@code @Nested} CLASS HERE RATHER THAN A FOURTH CONTAINER</b>,
 * deliberately. It needs exactly the same thing — the V21 chart of accounts — and
 * a MySQL container costs around half a minute to start. It is also the endpoint a
 * voucher screen calls to decide what these endpoints will accept, so the two
 * belong in one file: if A7's list and the picker's filter ever disagree, the
 * disagreement is visible on one screen of test output.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ManualVoucherService.class, LedgerPostingService.class,
        AccountDirectoryService.class, SettingService.class,
        ManualVoucherServiceTest.FixedClock.class})
@DisplayName("The vouchers a person composes, against MySQL")
class ManualVoucherServiceTest {

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            // Derived from today rather than pinned to a date, for the reason
            // RechargeLedgerPostingsTest records: a migration seeding
            // EFFECTIVE_FROM = CURDATE() makes a pinned clock fail on its own.
            return Clock.fixed(LocalDate.now().atTime(16, 20).atZone(ZoneId.systemDefault())
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
        // would create its own FK-less, CHECK-less tables and V21's
        // IF NOT EXISTS guards would then skip the real ones.
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
    private ManualVoucherService service;

    @Autowired
    private AccountDirectoryService directory;

    @Autowired
    private JdbcTemplate jdbc;

    private static final LocalDate TODAY = LocalDate.now();

    /**
     * ⚠ NULL, BECAUSE {@code fk_vch_creby} IS REAL. {@code acc_voucher.CRE_BY}
     * references {@code rc_user(id)} and the container's database has no users —
     * the legacy baseline creates the table, not its rows. Passing an id that
     * does not exist fails the INSERT, which is how this was found: seven tests
     * errored on "Cannot add or update a child row". {@code LedgerCloseServiceTest}
     * passes null for the same reason. The column is nullable on purpose, since a
     * voucher posted by a background path has no staff member behind it.
     */
    private static final Integer NO_STAFF = null;

    /**
     * ⚠ In foreign-key order. {@code acc_float_movement} points at
     * {@code acc_float_batch}, both point at {@code acc_voucher}, and
     * {@code acc_voucher_line} does too — so the parent goes last. The chart of
     * accounts is seeded by V21 and must survive: every assertion here reads it.
     */
    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM acc_float_movement");
        jdbc.update("DELETE FROM acc_float_batch");
        jdbc.update("DELETE FROM acc_reconciliation");
        jdbc.update("DELETE FROM acc_voucher_line");
        // ⚠ THE CORRECTING JOURNALS GO FIRST, because fk_vch_reverse is
        // SELF-REFERENCING: a journal holds the voucher it reverses alive, and
        // one DELETE cannot order itself. Every test after the first journal
        // errored on "Cannot delete or update a parent row" until this split.
        //
        // ⚠ And it is a DELETE rather than nulling the column, which was the
        // first attempt: V23's trigger 1 refused that with "acc_voucher is
        // immutable once sealed; post a correcting JOURNAL instead" — the
        // immutability guarantee working exactly as specified, on a test fixture.
        // Recorded because it is a useful thing to know about this schema: a
        // sealed voucher cannot be edited even to clean up after a test.
        jdbc.update("DELETE FROM acc_voucher WHERE REVERSES_VOUCHER_ID IS NOT NULL");
        jdbc.update("DELETE FROM acc_voucher");
    }

    // ==================================================================
    // Endpoint 7 — an expense
    // ==================================================================

    @Nested
    @DisplayName("#7 POST /vouchers/payments")
    class Payments {

        @Test
        @DisplayName("E19: Dr EXP_ELEC_WATER / Cr CASH_SAFE, and the safe falls by it")
        void postsTheExpense() {
            VoucherResponse posted = service.postPayment(
                    new PaymentRequest(TODAY, "EXP_ELEC_WATER", "CASH_SAFE",
                            new BigDecimal("1200.00"), "MSEB bill, Sept"),
                    "key-elec-1", NO_STAFF);

            assertEquals("PAYMENT", posted.voucherType());
            assertEquals("MANUAL", posted.sourceType());
            assertFalse(posted.replayed());
            assertNotNull(posted.sealedAt(), "an unsealed voucher is invisible to every report");
            assertEquals(2, posted.legs().size());
            assertLeg(posted, "EXP_ELEC_WATER", "1200.00", "0.00");
            assertLeg(posted, "CASH_SAFE", "0.00", "1200.00");

            // Through the view, not the legs: the balance is what a report reads.
            assertEquals(new BigDecimal("-1200.00"), balanceOf("CASH_SAFE"));
            assertEquals(new BigDecimal("1200.00"), balanceOf("EXP_ELEC_WATER"));
        }

        @Test
        @DisplayName("the key is stored, so a retry is the SAME voucher and not a second bill")
        void retryIsTheSameVoucher() {
            PaymentRequest request = new PaymentRequest(TODAY, "EXP_ELEC_WATER", "CASH_SAFE",
                    new BigDecimal("1200.00"), "MSEB bill, Sept");
            VoucherResponse first = service.postPayment(request, "key-elec-2", NO_STAFF);
            VoucherResponse replay = service.postPayment(request, "key-elec-2", NO_STAFF);

            assertFalse(first.replayed());
            assertTrue(replay.replayed(), "the controller turns this into the 409");
            assertEquals(first.voucherId(), replay.voucherId());
            assertEquals(1, countVouchers(), "a second voucher would be a second ₹1,200 bill");
            // The replayed body is read from the row, not echoed: S4 shipped a
            // replay that came back with nothing on it.
            assertEquals(2, replay.legs().size());
        }

        @Test
        @DisplayName("⚠ two REAL bills on one day are both accepted, under two keys")
        void twoRealBillsAreBothAccepted() {
            // The reason #7 takes a client key at all (§1.3): nothing in the
            // payload distinguishes this from a double-click, so the server must
            // not decide it is one.
            PaymentRequest first = new PaymentRequest(TODAY, "EXP_SUNDRY_SHOP", "CASH_SAFE",
                    new BigDecimal("1200.00"), "two identical bills, one");
            service.postPayment(first, "key-twin-a", NO_STAFF);
            service.postPayment(first, "key-twin-b", NO_STAFF);

            assertEquals(2, countVouchers());
            assertEquals(new BigDecimal("-2400.00"), balanceOf("CASH_SAFE"));
        }

        @Test
        @DisplayName("422 when the category is not an EXPENSE account")
        void categoryMustBeAnExpense() {
            LedgerPostingException refused = assertThrows(LedgerPostingException.class,
                    () -> service.postPayment(
                            new PaymentRequest(TODAY, "CASH_SAFE", "CASH_SAFE",
                                    new BigDecimal("10.00"), "nonsense"),
                            "key-bad-category", NO_STAFF));
            assertTrue(refused.getMessage().contains("needs an EXPENSE account"),
                    refused.getMessage());
            // The sentence has to say where to find a legal one.
            assertTrue(refused.getMessage().contains("manualOnly=true"), refused.getMessage());
        }

        @Test
        @DisplayName("⚠ 422 on EXP_CASH_SHORT, and the message sends you to the close")
        void cashShortIsNotHandPostable() {
            // A7. EXP_CASH_SHORT is posted by the close itself for the genuinely
            // unexplained (log §9.1); hand-posting it would put a KNOWN cause
            // into the account that answers "what can we not account for".
            LedgerPostingException refused = assertThrows(LedgerPostingException.class,
                    () -> service.postPayment(
                            new PaymentRequest(TODAY, "EXP_CASH_SHORT", "CASH_SAFE",
                                    new BigDecimal("50.00"), "drawer was light"),
                            "key-cash-short", NO_STAFF));
            assertTrue(refused.getMessage().contains("ALLOW_MANUAL = FALSE"),
                    refused.getMessage());
            assertTrue(refused.getMessage().contains("posted by the close itself"),
                    refused.getMessage());
        }

        @Test
        @DisplayName("⚠ 422 paying from UPI_CLEARING, and the message names endpoint 6")
        void upiClearingIsNotASourceOfCash() {
            LedgerPostingException refused = assertThrows(LedgerPostingException.class,
                    () -> service.postPayment(
                            new PaymentRequest(TODAY, "EXP_SUNDRY_SHOP", "UPI_CLEARING",
                                    new BigDecimal("50.00"), "paid out of the UPI money"),
                            "key-from-upi", NO_STAFF));
            assertTrue(refused.getMessage().contains("/accounts/upi-clearing/settlements"),
                    refused.getMessage());
        }

        @Test
        @DisplayName("422 on a non-positive amount and on a future date")
        void theTwoScalarRefusals() {
            assertTrue(assertThrows(LedgerPostingException.class, () -> service.postPayment(
                    new PaymentRequest(TODAY, "EXP_SUNDRY_SHOP", "CASH_SAFE",
                            new BigDecimal("0.00"), "nothing"), "key-zero", NO_STAFF))
                    .getMessage().contains("amount must be positive"));

            assertTrue(assertThrows(LedgerPostingException.class, () -> service.postPayment(
                    new PaymentRequest(TODAY.plusDays(1), "EXP_SUNDRY_SHOP", "CASH_SAFE",
                            new BigDecimal("10.00"), "tomorrow"), "key-future", NO_STAFF))
                    .getMessage().contains("is in the future"));
        }
    }

    // ==================================================================
    // Endpoint 8 — a transfer
    // ==================================================================

    @Nested
    @DisplayName("#8 POST /vouchers/contras")
    class Contras {

        @Test
        @DisplayName("E20: Dr BANK_JANATA / Cr CASH_SAFE, and P&L does not move")
        void postsTheTransfer() {
            service.postPayment(new PaymentRequest(TODAY, "EXP_SUNDRY_SHOP", "CASH_SAFE",
                    new BigDecimal("0.01"), "seed"), "key-seed", NO_STAFF);
            BigDecimal plBefore = balanceOf("EXP_SUNDRY_SHOP");

            VoucherResponse posted = service.postContra(
                    new ContraRequest(TODAY, "CASH_SAFE", "BANK_JANATA",
                            new BigDecimal("5000.00"), "Weekly deposit, Janata counter"),
                    "key-contra-1", NO_STAFF);

            assertEquals("CONTRA", posted.voucherType());
            assertLeg(posted, "BANK_JANATA", "5000.00", "0.00");
            assertLeg(posted, "CASH_SAFE", "0.00", "5000.00");
            assertEquals(new BigDecimal("5000.00"), balanceOf("BANK_JANATA"));
            // FRD §8.2's pure asset relocation: AFFECTS_PL = FALSE is data on
            // ref_voucher_type, and this is the assertion that it means something.
            assertEquals(plBefore, balanceOf("EXP_SUNDRY_SHOP"));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT AFFECTS_PL FROM ref_voucher_type WHERE CODE = 'CONTRA'",
                    Integer.class));
        }

        @Test
        @DisplayName("⚠ 422 when from and to are the same account")
        void aSelfContraIsRefused() {
            // It balances, passes every check and moves nothing — which is
            // exactly why LedgerCloseService refuses poolCode = SAFE too.
            LedgerPostingException refused = assertThrows(LedgerPostingException.class,
                    () -> service.postContra(
                            new ContraRequest(TODAY, "CASH_SAFE", "CASH_SAFE",
                                    new BigDecimal("100.00"), "round trip"),
                            "key-self-contra", NO_STAFF));
            assertTrue(refused.getMessage().contains("moves nothing"), refused.getMessage());
        }

        @Test
        @DisplayName("⚠ 422 on a float, and the message gives BOTH endpoints that move one")
        void aFloatIsNotContraed() {
            LedgerPostingException refused = assertThrows(LedgerPostingException.class,
                    () -> service.postContra(
                            new ContraRequest(TODAY, "BANK_JANATA", "FLOAT_RJIO",
                                    new BigDecimal("10000.00"), "top up by hand"),
                            "key-float-contra", NO_STAFF));
            assertTrue(refused.getMessage().contains("/vouchers/purchases"), refused.getMessage());
            assertTrue(refused.getMessage().contains("RATE_CORRECTION"), refused.getMessage());
        }

        @Test
        @DisplayName("422 when an account is not cash or bank")
        void onlyCashAndBank() {
            assertTrue(assertThrows(LedgerPostingException.class, () -> service.postContra(
                    new ContraRequest(TODAY, "CASH_SAFE", "EXP_SUNDRY_SHOP",
                            new BigDecimal("100.00"), "not a transfer"), "key-exp-contra", NO_STAFF))
                    .getMessage().contains("CASH_AND_BANK"));
        }

        @Test
        @DisplayName("the tills ARE accepted — the screen declines them, the rule does not")
        void tillsAreAcceptedByTheEndpoint() {
            // §1.3: #8's purpose line says "and the manual sweep". The screen
            // leaves the tills out because they empty themselves nightly, which
            // is a screen decision and not a rule.
            VoucherResponse posted = service.postContra(
                    new ContraRequest(TODAY, "CASH_RECHARGE", "CASH_SAFE",
                            new BigDecimal("2000.00"), "sweep by hand"),
                    "key-manual-sweep", NO_STAFF);
            assertEquals(new BigDecimal("-2000.00"), balanceOf("CASH_RECHARGE"));
            assertEquals(new BigDecimal("2000.00"), balanceOf("CASH_SAFE"));
            assertEquals("CONTRA", posted.voucherType());
        }
    }

    // ==================================================================
    // Endpoint 9 — a correction
    // ==================================================================

    @Nested
    @DisplayName("#9 POST /vouchers/journals")
    class Journals {

        @Test
        @DisplayName("a wrong expense category is corrected, dated the day it was found")
        void correctsAMisPostedExpense() {
            VoucherResponse original = service.postPayment(
                    new PaymentRequest(TODAY.minusDays(3), "EXP_ELEC_WATER", "CASH_SAFE",
                            new BigDecimal("1200.00"), "booked as electricity"),
                    "key-wrong-category", NO_STAFF);

            VoucherResponse journal = service.postJournal(new JournalRequest(
                    TODAY,
                    original.voucherId(),
                    List.of(new JournalLeg("EXP_MAINT_WATER", new BigDecimal("1200.00"),
                                    null, null, null),
                            new JournalLeg("EXP_ELEC_WATER", null, new BigDecimal("1200.00"),
                                    null, null)),
                    "1,200 posted as electricity on " + TODAY.minusDays(3)
                            + ", was pump maintenance"), "key-journal-1", NO_STAFF);

            assertEquals("JOURNAL", journal.voucherType());
            // CORRECTION rather than MANUAL, because it names what it reverses.
            assertEquals("CORRECTION", journal.sourceType());
            assertEquals(original.voucherId(), journal.reversesVoucherId());
            assertEquals(TODAY, journal.voucherDate(), "FRD §8.3: the day the mistake was FOUND");
            assertEquals(BigDecimal.ZERO.setScale(2), balanceOf("EXP_ELEC_WATER"));
            assertEquals(new BigDecimal("1200.00"), balanceOf("EXP_MAINT_WATER"));
            // The original is untouched: a correction is a new voucher, never an
            // edit (V23 trigger 1 would refuse the edit anyway).
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM acc_voucher_line WHERE VOUCHER_ID = ? "
                            + "AND ACCOUNT_CODE = 'EXP_ELEC_WATER' AND DR_AMOUNT = 1200.00",
                    Integer.class, original.voucherId()));
        }

        @Test
        @DisplayName("⚠ accepted AFTER the account is reconciled — the 422 was withdrawn")
        void aJournalIsAcceptedAfterAClose() {
            // The rule withdrawn on 2026-11-02. A row on CASH_SAFE dated today
            // seals every CASH_SAFE voucher on or before today against
            // delete-and-repost, and the journal is the correction route that
            // replaces it. Refusing here is what left an expense with no route
            // at all until its drawer was counted.
            jdbc.update("INSERT INTO acc_reconciliation (ACCOUNT_CODE, RECON_DATE, "
                    + "COUNTED_AMOUNT, LEDGER_AMOUNT, CLOSED_AT) "
                    + "VALUES ('CASH_SAFE', ?, 0.00, 0.00, NOW())", TODAY);

            VoucherResponse journal = service.postJournal(new JournalRequest(
                    TODAY, null,
                    List.of(new JournalLeg("EXP_SUNDRY_SHOP", new BigDecimal("40.00"),
                                    null, null, null),
                            new JournalLeg("CASH_SAFE", null, new BigDecimal("40.00"),
                                    null, null)),
                    "photocopy paper, missed on the day"), "key-journal-after-close", NO_STAFF);

            assertNotNull(journal.sealedAt());
            assertEquals(new BigDecimal("-40.00"), balanceOf("CASH_SAFE"));
        }

        @Test
        @DisplayName("⚠ §1.3's own journal example is refused by A7, and names who may post it")
        void thePublishedExampleIsRefused() {
            // §1.3's example payload is Dr INC_RCHG_COMM 50 / Cr CASH_RECHARGE 50
            // ("₹100 sale posted as ₹150 on 28 Sept"), and INC_RCHG_COMM is
            // ALLOW_MANUAL = FALSE (V21:301) — so the example cannot be posted
            // through the endpoint it illustrates. The RULE is what §1.3 states as
            // enforcement ("no account with ALLOW_MANUAL = FALSE (A7)"), the
            // example is what is wrong, and the correction is written into the
            // design document. A recharge sale's correction is posted BY the
            // recharge module, which is exactly what the withdrawn-422 note says.
            LedgerPostingException refused = assertThrows(LedgerPostingException.class,
                    () -> service.postJournal(new JournalRequest(
                            TODAY, null,
                            List.of(new JournalLeg("INC_RCHG_COMM", new BigDecimal("50.00"),
                                            null, null, null),
                                    new JournalLeg("CASH_RECHARGE", null,
                                            new BigDecimal("50.00"), null, null)),
                            "100 sale posted as 150 on 28 Sept"), "key-published-example", NO_STAFF));
            assertTrue(refused.getMessage().contains("INC_RCHG_COMM"), refused.getMessage());
            assertTrue(refused.getMessage().contains("SUM(A x r)"), refused.getMessage());
            assertEquals(0, countVouchers());
        }

        @Test
        @DisplayName("422 when the legs do not balance, with the difference in the sentence")
        void mustBalance() {
            LedgerPostingException refused = assertThrows(LedgerPostingException.class,
                    () -> service.postJournal(new JournalRequest(
                            TODAY, null,
                            List.of(new JournalLeg("EXP_SUNDRY_SHOP", new BigDecimal("50.00"),
                                            null, null, null),
                                    new JournalLeg("CASH_SAFE", null, new BigDecimal("40.00"),
                                            null, null)),
                            "lost a tenner"), "key-unbalanced", NO_STAFF));
            assertTrue(refused.getMessage().contains("DR 50.00 vs CR 40.00"),
                    refused.getMessage());
            assertTrue(refused.getMessage().contains("difference is 10.00"),
                    refused.getMessage());
        }

        @Test
        @DisplayName("422 on one leg, on a zero leg, and on a leg that is both sides")
        void legShapes() {
            assertTrue(assertThrows(LedgerPostingException.class, () -> service.postJournal(
                    new JournalRequest(TODAY, null,
                            List.of(new JournalLeg("CASH_SAFE", new BigDecimal("10.00"),
                                    null, null, null)),
                            "half a journal"), "key-one-leg", NO_STAFF))
                    .getMessage().contains("at least two legs"));

            assertTrue(assertThrows(LedgerPostingException.class, () -> service.postJournal(
                    new JournalRequest(TODAY, null,
                            List.of(new JournalLeg("CASH_SAFE", new BigDecimal("0.00"),
                                            null, null, null),
                                    new JournalLeg("EXP_SUNDRY_SHOP", null,
                                            new BigDecimal("0.00"), null, null)),
                            "nothing at all"), "key-zero-legs", NO_STAFF))
                    .getMessage().contains("a leg cannot be zero"));

            assertTrue(assertThrows(LedgerPostingException.class, () -> service.postJournal(
                    new JournalRequest(TODAY, null,
                            List.of(new JournalLeg("CASH_SAFE", new BigDecimal("10.00"),
                                            new BigDecimal("10.00"), null, null),
                                    new JournalLeg("EXP_SUNDRY_SHOP", new BigDecimal("10.00"),
                                            null, null, null)),
                            "both sides"), "key-both-sides", NO_STAFF))
                    .getMessage().contains("never both"));
        }

        @Test
        @DisplayName("422 reversing a voucher that does not exist")
        void reversesMustExist() {
            assertTrue(assertThrows(LedgerPostingException.class, () -> service.postJournal(
                    new JournalRequest(TODAY, 999999L,
                            List.of(new JournalLeg("EXP_SUNDRY_SHOP", new BigDecimal("10.00"),
                                            null, null, null),
                                    new JournalLeg("CASH_SAFE", null, new BigDecimal("10.00"),
                                            null, null)),
                            "reverses thin air"), "key-no-such-voucher", NO_STAFF))
                    .getMessage().contains("no such voucher to reverse: 999999"));
        }

        @Test
        @DisplayName("⚠ 422 on an AR_* leg with no customer on it")
        void receivablesNeedAParty() {
            // AR_RECHARGE is ALLOW_MANUAL = FALSE, so A7 answers first and the
            // party rule is unreachable through this endpoint today. Asserted on
            // the A7 message rather than faked, so the test says what actually
            // happens: this is the refusal a caller gets.
            LedgerPostingException refused = assertThrows(LedgerPostingException.class,
                    () -> service.postJournal(new JournalRequest(
                            TODAY, null,
                            List.of(new JournalLeg("AR_RECHARGE", new BigDecimal("10.00"),
                                            null, null, null),
                                    new JournalLeg("CASH_SAFE", null, new BigDecimal("10.00"),
                                            null, null)),
                            "someone owes us"), "key-ar-no-party", NO_STAFF));
            assertTrue(refused.getMessage().contains("ALLOW_MANUAL = FALSE"),
                    refused.getMessage());
        }
    }

    // ==================================================================
    // Endpoint 19 — a float top-up
    // ==================================================================

    @Nested
    @DisplayName("#19 POST /vouchers/purchases")
    class Purchases {

        @Test
        @DisplayName("E6: three legs, and the ₹5 is its OWN expense rather than float cost")
        void postsTheThreeLegs() {
            VoucherResponse posted = service.postPurchase(
                    new PurchaseRequest(TODAY, "FLOAT_A1TOPUP", new BigDecimal("10000.00"),
                            "BANK_JANATA", null, null, "IMPS-99812", "IMPS to A1Topup"),
                    "key-topup-1", NO_STAFF);

            assertEquals("PURCHASE", posted.voucherType());
            assertEquals("FLOAT_TOPUP", posted.sourceType());
            assertEquals(3, posted.legs().size());
            assertLeg(posted, "FLOAT_A1TOPUP", "10000.00", "0.00");
            // The fee defaults to rc_setting['float.imps.fee'], seeded 5.00.
            assertLeg(posted, "EXP_BANK_CHARGES", "5.00", "0.00");
            assertLeg(posted, "BANK_JANATA", "0.00", "10005.00");

            // ⚠ THE RATIO IS THE WHOLE POINT. The float carries 10,000 and not
            // 10,005, so cost over face is exactly 1.0 and the portal comparison
            // stays a straight one (log §2.5:114-118).
            assertEquals(new BigDecimal("10000.00"), balanceOf("FLOAT_A1TOPUP"));
            assertEquals(new BigDecimal("5.00"), balanceOf("EXP_BANK_CHARGES"));
            assertEquals(new BigDecimal("-10005.00"), balanceOf("BANK_JANATA"));
        }

        @Test
        @DisplayName("the batch and the movement row, with cost = face and the fee excluded")
        void recordsTheBatchAndTheMovement() {
            VoucherResponse posted = service.postPurchase(
                    new PurchaseRequest(TODAY, "FLOAT_RJIO", new BigDecimal("10000.00"),
                            "BANK_JANATA", null, null, "IMPS-11", null),
                    "key-topup-2", NO_STAFF);

            assertEquals(new BigDecimal("10000.00"), jdbc.queryForObject(
                    "SELECT FACE_ADDED FROM acc_float_batch WHERE TOPUP_VOUCHER_ID = ?",
                    BigDecimal.class, posted.voucherId()));
            assertEquals(new BigDecimal("10000.00"), jdbc.queryForObject(
                    "SELECT COST_PAID FROM acc_float_batch WHERE TOPUP_VOUCHER_ID = ?",
                    BigDecimal.class, posted.voucherId()));
            assertEquals("IMPS-11", jdbc.queryForObject(
                    "SELECT EXTERNAL_REF FROM acc_float_batch WHERE TOPUP_VOUCHER_ID = ?",
                    String.class, posted.voucherId()));

            // ck_fmov_topup demands a BATCH_ID on a TOPUP; this is what satisfies
            // it, and the ratio below is §3.4's derived weighted average.
            // compareTo, not equals: MySQL answers this division at its own
            // scale (1.000000) and BigDecimal.equals is scale-sensitive. The
            // RATIO is what matters and it is exactly 1 — which is the parity
            // §3.3 depends on and what keeps the portal comparison straight.
            assertEquals(0, BigDecimal.ONE.compareTo(jdbc.queryForObject(
                    "SELECT SUM(COST_DELTA) / NULLIF(SUM(FACE_DELTA), 0) "
                            + "FROM acc_float_movement WHERE ACCOUNT_CODE = 'FLOAT_RJIO'",
                    BigDecimal.class)), "§3.4's derived weighted average, at parity");
            assertEquals("TOPUP", jdbc.queryForObject(
                    "SELECT MOVEMENT_TYPE FROM acc_float_movement WHERE VOUCHER_ID = ?",
                    String.class, posted.voucherId()));
            assertNotNull(jdbc.queryForObject(
                    "SELECT BATCH_ID FROM acc_float_movement WHERE VOUCHER_ID = ?",
                    Long.class, posted.voucherId()));

            // ⚠ V24's first identity: SUM(COST_DELTA) == the GL balance.
            assertEquals(balanceOf("FLOAT_RJIO"), jdbc.queryForObject(
                    "SELECT SUM(COST_DELTA) FROM acc_float_movement "
                            + "WHERE ACCOUNT_CODE = 'FLOAT_RJIO'", BigDecimal.class));
        }

        @Test
        @DisplayName("⚠ E7: a cash top-up has TWO legs, because a zero leg is illegal")
        void aZeroFeeOmitsTheLeg() {
            // "no IMPS fee arises" on cash (E7), and ck_line_nonzero
            // CHECK (DR_AMOUNT + CR_AMOUNT > 0) means posting the fee leg at 0.00
            // does not make a junk line — it FAILS THE WHOLE TOP-UP. The same
            // defect §1.1 found in the evening sweep, arriving by another route.
            VoucherResponse posted = service.postPurchase(
                    new PurchaseRequest(TODAY, "FLOAT_ARTL", new BigDecimal("2000.00"),
                            "CASH_RECHARGE", BigDecimal.ZERO, null, null, "cash top-up"),
                    "key-topup-cash", NO_STAFF);

            assertEquals(2, posted.legs().size());
            assertLeg(posted, "FLOAT_ARTL", "2000.00", "0.00");
            assertLeg(posted, "CASH_RECHARGE", "0.00", "2000.00");
            assertEquals(BigDecimal.ZERO.setScale(2), balanceOf("EXP_BANK_CHARGES"));
        }

        @Test
        @DisplayName("⚠ a replay writes no second batch, which would double the cost basis")
        void replayWritesNoSecondBatch() {
            PurchaseRequest request = new PurchaseRequest(TODAY, "FLOAT_VI",
                    new BigDecimal("5000.00"), "BANK_JANATA", null, null, null, "top-up");
            VoucherResponse first = service.postPurchase(request, "key-topup-replay", NO_STAFF);
            VoucherResponse replay = service.postPurchase(request, "key-topup-replay", NO_STAFF);

            assertTrue(replay.replayed());
            assertEquals(first.voucherId(), replay.voucherId());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM acc_float_batch", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM acc_float_movement", Integer.class));
            assertEquals(new BigDecimal("5000.00"), balanceOf("FLOAT_VI"));
        }

        @Test
        @DisplayName("422 when the account is not a float")
        void onlyAFloatIsToppedUp() {
            assertTrue(assertThrows(LedgerPostingException.class, () -> service.postPurchase(
                    new PurchaseRequest(TODAY, "CASH_SAFE", new BigDecimal("100.00"),
                            "BANK_JANATA", null, null, null, null), "key-not-a-float", NO_STAFF))
                    .getMessage().contains("no FLOAT_PATTERN"));
        }

        @Test
        @DisplayName("⚠ 422 when costPaid disagrees with amount, because every wallet is 1.0")
        void costMustEqualFaceAtParity() {
            LedgerPostingException refused = assertThrows(LedgerPostingException.class,
                    () -> service.postPurchase(new PurchaseRequest(TODAY, "FLOAT_RJIO",
                                    new BigDecimal("10000.00"), "BANK_JANATA", null,
                                    new BigDecimal("9800.00"), null, "bonus that does not exist"),
                            "key-bad-cost", NO_STAFF));
            assertTrue(refused.getMessage().contains("TXN_RATE"), refused.getMessage());
            assertTrue(refused.getMessage().contains("ratio is exactly 1.0"),
                    refused.getMessage());
        }

        @Test
        @DisplayName("422 on a negative fee")
        void negativeFee() {
            assertTrue(assertThrows(LedgerPostingException.class, () -> service.postPurchase(
                    new PurchaseRequest(TODAY, "FLOAT_RJIO", new BigDecimal("100.00"),
                            "BANK_JANATA", new BigDecimal("-5.00"), null, null, null),
                    "key-negative-fee", NO_STAFF))
                    .getMessage().contains("transferFee cannot be negative"));
        }
    }

    // ==================================================================
    // Endpoint 12 — the picker list
    // ==================================================================

    @Nested
    @DisplayName("#12 GET /accounts")
    class Directory {

        @Test
        @DisplayName("⚠ manualOnly=true is exactly what the voucher endpoints accept")
        void manualOnlyMatchesA7() {
            // The assertion that matters: the picker and A7 cannot drift, because
            // this compares the list against the FLAG the services check.
            List<AccountResponse> manual = directory.directory(true, null, null);
            assertTrue(manual.stream().allMatch(AccountResponse::allowManual));
            assertTrue(manual.stream().noneMatch(a -> a.accountCode().equals("UPI_CLEARING")));
            assertTrue(manual.stream().noneMatch(a -> a.floatPattern() != null));
            assertTrue(manual.stream().noneMatch(a -> a.accountCode().equals("EXP_CASH_SHORT")));
            assertTrue(manual.stream().noneMatch(a -> a.accountCode().equals("INC_CASH_OVER")));
            // And V34's account IS there, which is the whole reason V34 exists:
            // before it, no INCOME account could be named by hand at all.
            assertTrue(manual.stream().anyMatch(a -> a.accountCode().equals("INC_SUNDRY_SHOP")));
        }

        @Test
        @DisplayName("the eight accounts a contra accepts come back under one filter")
        void theCashAndBankEight() {
            List<String> codes = directory.directory(true, "ASSET", null).stream()
                    .filter(a -> "CASH_AND_BANK".equals(a.reportGroup()))
                    .map(AccountResponse::accountCode)
                    .toList();
            assertEquals(List.of("CASH_WATER", "CASH_RECHARGE", "CASH_TICKET", "CASH_SAFE",
                    "BANK_JANATA", "BANK_SELF", "BANK_WIFE", "BANK_FATHER"), codes,
                    "§1.3: 'that leaves eight accounts the endpoint will accept', in SORT_ORDER");
        }

        @Test
        @DisplayName("the pool columns come back, since a picker shows the drawer")
        void poolMembershipIsOnTheRows() {
            AccountResponse recharge = directory.directory(false, null, null).stream()
                    .filter(a -> a.accountCode().equals("CASH_RECHARGE"))
                    .findFirst().orElseThrow();
            assertEquals("POOL-1", recharge.cashPool());
            assertTrue(recharge.isPoolPrimary(), "A2: the member the variance lands on");
        }

        @Test
        @DisplayName("an unknown type is an empty list rather than a 400")
        void unknownFilterIsEmpty() {
            assertTrue(directory.directory(false, "NOT_A_TYPE", null).isEmpty());
            // And a blank parameter is not a filter at all.
            assertFalse(directory.directory(false, "", "").isEmpty());
        }

        @Test
        @DisplayName("every row is active — a retired account must never be offered")
        void onlyActiveAccounts() {
            jdbc.update("UPDATE acc_account SET IS_ACTIVE = FALSE "
                    + "WHERE ACCOUNT_CODE = 'EXP_SUNDRY_SHOP'");
            try {
                assertTrue(directory.directory(false, null, null).stream()
                        .noneMatch(a -> a.accountCode().equals("EXP_SUNDRY_SHOP")),
                        "IS_ACTIVE is not a filter a caller can switch off");
            } finally {
                jdbc.update("UPDATE acc_account SET IS_ACTIVE = TRUE "
                        + "WHERE ACCOUNT_CODE = 'EXP_SUNDRY_SHOP'");
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private void assertLeg(VoucherResponse posted, String accountCode, String dr, String cr) {
        VoucherResponse.Leg leg = posted.legs().stream()
                .filter(l -> l.accountCode().equals(accountCode))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no leg on " + accountCode + "; legs were " + posted.legs()));
        assertEquals(new BigDecimal(dr), leg.dr(), accountCode + " debit");
        assertEquals(new BigDecimal(cr), leg.cr(), accountCode + " credit");
    }

    /** Through V26's view, which is what every report reads. */
    private BigDecimal balanceOf(String code) {
        return jdbc.queryForObject(
                "SELECT BALANCE FROM acc_account_balance WHERE ACCOUNT_CODE = ?",
                BigDecimal.class, code);
    }

    private int countVouchers() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM acc_voucher", Integer.class);
    }
}
