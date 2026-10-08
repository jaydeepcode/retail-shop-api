package com.mangle.retailshopapp.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two rules that moved out of the database on 2026-10-09.
 *
 * <p>These tests are the reason that change counts as <i>moving</i> the rules
 * rather than deleting them. V23's {@code trg_acc_voucher_line_bd} and V25's
 * {@code trg_acc_commission_rate_bu} are gone; without these the rules would
 * exist nowhere.
 *
 * <p>The rate rules take their dates as parameters and need no database, so they
 * sit in a {@code @Nested} class with no container. The voucher-deletable rule
 * reads {@code acc_reconciliation} and {@code acc_voucher_line}, so it needs the
 * real schema.
 */
@DisplayName("the rules that moved from triggers into the service")
class LedgerCorrectionRulesTest {

    // ------------------------------------------------------------------
    // Rate rules - no database needed
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("commission rate: closed and superseded, never edited")
    class RateRules {

        private final LedgerCorrectionRules rules = new LedgerCorrectionRules(null, null);
        private final LocalDate today = LocalDate.of(2026, 10, 9);
        private final LocalDate from = LocalDate.of(2026, 10, 1);

        @Test
        @DisplayName("closing an open rate from today onwards is allowed")
        void closingTodayIsFine() {
            assertDoesNotThrow(() -> rules.assertRateClosable(null, today, from, today));
            assertDoesNotThrow(() -> rules.assertRateClosable(
                    null, today.plusDays(30), from, today));
        }

        @Test
        @DisplayName("a rate already closed cannot be re-closed or re-opened")
        void alreadyClosed() {
            // The trigger's second branch. Both directions are the same defect:
            // EFFECTIVE_TO is write-once.
            assertTrue(assertThrows(LedgerPostingException.class,
                    () -> rules.assertRateClosable(today, today.plusDays(1), from, today))
                    .getMessage().contains("already closed"));
            assertTrue(assertThrows(LedgerPostingException.class,
                    () -> rules.assertRateClosable(today, null, from, today))
                    .getMessage().contains("already closed"));
        }

        @Test
        @DisplayName("a rate cannot be closed in the past, and the message says why")
        void noBackdatedClose() {
            // FRD §7.2: "past recharges keep the commission amount that was
            // actually computed and posted at the time." Backdating a close
            // would mean a later lookup resolves a different rate for a day
            // whose sales are already posted.
            LedgerPostingException e = assertThrows(LedgerPostingException.class,
                    () -> rules.assertRateClosable(null, today.minusDays(1), from, today));
            assertTrue(e.getMessage().contains("cannot be closed in the past"), e.getMessage());
            // The message has to point at the remedy, which is the whole reason
            // this moved out of a SIGNAL SQLSTATE '45000'.
            assertTrue(e.getMessage().contains("correcting JOURNAL"), e.getMessage());
        }

        @Test
        @DisplayName("EFFECTIVE_TO cannot precede EFFECTIVE_FROM")
        void periodMustBeOrdered() {
            // ck_rate_period covers this in the DB too; the service repeats it
            // so the user gets a sentence rather than a constraint name.
            assertTrue(assertThrows(LedgerPostingException.class,
                    () -> rules.assertRateClosable(
                            null, LocalDate.of(2026, 9, 1), from, LocalDate.of(2026, 8, 1)))
                    .getMessage().contains("cannot precede EFFECTIVE_FROM"));
        }

        @Test
        @DisplayName("any change to an identifying column is refused")
        void identityIsImmutable() {
            assertDoesNotThrow(() -> rules.assertRateIdentityUnchanged(
                    false, false, false, false, false, false));
            // ROUTE_CODE is in the list because log §8.3 put it there: the same
            // operator earns different rates by route, so route is part of the
            // rate's identity, not a detail of it.
            for (int i = 0; i < 6; i++) {
                boolean[] f = new boolean[6];
                f[i] = true;
                assertTrue(assertThrows(LedgerPostingException.class,
                        () -> rules.assertRateIdentityUnchanged(f[0], f[1], f[2], f[3], f[4], f[5]))
                        .getMessage().contains("close it and insert a successor"),
                        "changing column " + i + " must be refused");
            }
        }
    }

    // ------------------------------------------------------------------
    // The reconciliation delete lock - needs the real schema
    // ------------------------------------------------------------------

    @Nested
    @Testcontainers(disabledWithoutDocker = true)
    @DataJpaTest
    @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
    @Import({LedgerPostingService.class, LedgerCorrectionRules.class})
    @DisplayName("FRD 8.3: a reconciled account can only be corrected by a journal")
    class DeleteLock {

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
            registry.add("spring.flyway.target", () -> "27");
            registry.add("spring.flyway.out-of-order", () -> "false");
        }

        @Autowired
        private LedgerPostingService posting;
        @Autowired
        private LedgerCorrectionRules rules;
        @Autowired
        private EntityManager em;

        private long postOne(LocalDate date) {
            return posting.post(new VoucherDraft(
                    "JOURNAL", date, date.atTime(11, 0), "lock probe", "MANUAL",
                    null, null, null, null,
                    List.of(VoucherLeg.debit("CASH_RECHARGE", new BigDecimal("50.00")),
                            VoucherLeg.credit("INC_RCHG_COMM", new BigDecimal("50.00")))))
                    .voucherId();
        }

        private void reconcile(String account, LocalDate on) {
            em.createNativeQuery("INSERT INTO acc_reconciliation (ACCOUNT_CODE, RECON_DATE, "
                            + "COUNTED_AMOUNT, LEDGER_AMOUNT) VALUES (:a, :d, 0.00, 0.00)")
                    .setParameter("a", account).setParameter("d", on)
                    .executeUpdate();
        }

        @Test
        @DisplayName("deletable while no reconciliation covers its accounts")
        void deletableBeforeTheReconciliationPoint() {
            // Before the reconciliation point the whole voucher can be deleted
            // and re-posted, which from the user's point of view is an edit.
            long id = postOne(LocalDate.of(2026, 10, 8));
            assertDoesNotThrow(() -> rules.assertVoucherDeletable(id));
        }

        @Test
        @DisplayName("refused once an account it touches is reconciled on that day")
        void lockedOnTheReconciliationDate() {
            long id = postOne(LocalDate.of(2026, 10, 8));
            reconcile("CASH_RECHARGE", LocalDate.of(2026, 10, 8));

            LedgerPostingException e = assertThrows(LedgerPostingException.class,
                    () -> rules.assertVoucherDeletable(id));
            assertTrue(e.getMessage().contains("CASH_RECHARGE"), e.getMessage());
            // The remedy has to be in the message; that is the point of moving
            // this out of the database.
            assertTrue(e.getMessage().contains("correcting JOURNAL"), e.getMessage());
        }

        @Test
        @DisplayName("refused when the reconciliation is later than the voucher")
        void lockedByALaterReconciliation() {
            // The rule is RECON_DATE >= VOUCHER_DATE, not equality: closing
            // Thursday must lock Tuesday's vouchers too, or a past day stays
            // editable after its drawer was counted.
            long id = postOne(LocalDate.of(2026, 10, 6));
            reconcile("INC_RCHG_COMM", LocalDate.of(2026, 10, 9));
            assertThrows(LedgerPostingException.class, () -> rules.assertVoucherDeletable(id));
        }

        @Test
        @DisplayName("an earlier reconciliation does not lock a later voucher")
        void earlierReconciliationDoesNotLock() {
            long id = postOne(LocalDate.of(2026, 10, 10));
            reconcile("CASH_RECHARGE", LocalDate.of(2026, 10, 9));
            assertDoesNotThrow(() -> rules.assertVoucherDeletable(id));
        }

        @Test
        @DisplayName("a reconciliation on an account the voucher does not touch does not lock it")
        void unrelatedAccountDoesNotLock() {
            // The join is on ACCOUNT_CODE for this voucher's own legs. Closing
            // the water drawer must not freeze a recharge voucher.
            long id = postOne(LocalDate.of(2026, 10, 8));
            reconcile("CASH_WATER", LocalDate.of(2026, 10, 8));
            assertDoesNotThrow(() -> rules.assertVoucherDeletable(id));
        }

        @Test
        @DisplayName("the OPENING voucher is refused outright, with its own message")
        void openingVoucherNeverDeletable() {
            long id = posting.post(new VoucherDraft(
                    "JOURNAL", LocalDate.of(2026, 10, 8), LocalDateTime.of(2026, 10, 8, 11, 0),
                    "opening", "OPENING", null, null, null, null,
                    List.of(VoucherLeg.debit("CASH_RECHARGE", new BigDecimal("1.00")),
                            VoucherLeg.credit("OWNERS_EQUITY", new BigDecimal("1.00")))))
                    .voucherId();

            // V23's trg_acc_voucher_bd is the guarantee; this is the readable
            // message, and the duplication is deliberate.
            assertTrue(assertThrows(LedgerPostingException.class,
                    () -> rules.assertVoucherDeletable(id))
                    .getMessage().contains("opening balance voucher cannot be deleted"));
        }

        @Test
        @DisplayName("an unknown voucher id is a refusal, not a silent pass")
        void unknownVoucher() {
            assertTrue(assertThrows(LedgerPostingException.class,
                    () -> rules.assertVoucherDeletable(999_999L))
                    .getMessage().contains("no such voucher"));
        }
    }
}
