package com.mangle.retailshopapp.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.mangle.retailshopapp.accounting.repo.AccVoucherLineRepository;
import com.mangle.retailshopapp.accounting.repo.AccVoucherRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code LedgerPostingService} against real MySQL with V23's triggers live.
 *
 * <p>The container is not optional here. The service's three-step post exists
 * precisely to satisfy a trigger, so testing it on H2 — where no trigger exists —
 * would assert that the Java compiles rather than that the posting works. The
 * normal test profile stays on H2 with Flyway off, so this class overrides the
 * datasource, turns Flyway on and caps it at V27, exactly as the migration gate
 * does.
 *
 * <p>{@code @DataJpaTest} wraps each test in a transaction that rolls back, which
 * is also what satisfies {@code MANDATORY} — so the one test that must run
 * WITHOUT a transaction opts out explicitly with {@code Propagation.NEVER}.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(LedgerPostingService.class)
@DisplayName("LedgerPostingService against MySQL with the triggers live")
class LedgerPostingServiceTest {

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
        // would create its own FK-less, trigger-less tables and the IF NOT EXISTS
        // guards would then skip the real ones — the exact damage V27 exists to
        // catch (implementation-plan.md §0.1).
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
    private LedgerPostingService service;
    @Autowired
    private AccVoucherRepository voucherRepository;
    @Autowired
    private AccVoucherLineRepository lineRepository;

    // ------------------------------------------------------------------
    // The reason MANDATORY is in the design at all
    // ------------------------------------------------------------------

    @Test
    @Transactional(propagation = Propagation.NEVER)
    @DisplayName("called with no transaction, it throws rather than opening one")
    void mandatoryPropagationThrowsOutsideATransaction() {
        // §5.1 calls this the single most valuable line in the design. With
        // REQUIRED, a caller that forgot @Transactional would silently get its
        // own transaction, the ledger write would commit separately from the
        // operational write, and FRD §8.5's guarantee would evaporate with
        // nothing failing to say so. This is the test that cannot pass if
        // someone later "fixes" MANDATORY to REQUIRED.
        assertThrows(IllegalTransactionStateException.class,
                () -> service.post(balanced("MANUAL-no-tx")));
    }

    // ------------------------------------------------------------------
    // The happy path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a balanced voucher is written and sealed in one call")
    void postsAndSeals() {
        PostedVoucher posted = service.post(balanced("RECHARGE_SALE:rc_txn_header#1:SETTLED"));

        assertTrue(posted.voucherId() > 0);
        assertEquals(2, posted.legCount());
        assertFalse(posted.replayed());
        // Sealing is what asserts the balance (V23 trigger 1). An unsealed
        // voucher would mean the third step never ran.
        assertNotNull(posted.sealedAt(), "the post must leave the voucher sealed");

        var saved = voucherRepository.findById(posted.voucherId()).orElseThrow();
        assertNotNull(saved.getSealedAt());
        assertEquals("RECHARGE_SALE:rc_txn_header#1:SETTLED", saved.getIdempotencyKey());

        // LINE_NO is assigned by the service, 1..n, so a caller cannot leave a
        // gap or a repeat and violate uk_line_voucher_no.
        var legs = lineRepository.findByVoucherIdOrderByLineNo(posted.voucherId());
        assertEquals(List.of((short) 1, (short) 2),
                legs.stream().map(l -> l.getLineNo()).toList());
        assertEquals(0, legs.get(0).getDrAmount().compareTo(new BigDecimal("199.00")));
        assertEquals(0, legs.get(1).getCrAmount().compareTo(new BigDecimal("199.00")));
    }

    @Test
    @DisplayName("a three-leg recharge income post balances to the paisa")
    void postsThreeLegRechargeIncome() {
        // §3.3's arithmetic for a Rs 199 Airtel sale on the direct route at
        // 3.00%: face = 199 x (1 - 0.03) = 193.03, and commission is computed BY
        // SUBTRACTION (199 - 193.03) rather than as 199 x 0.03 rounded
        // separately. That is what makes the voucher balance with no plug figure.
        PostedVoucher posted = service.post(new VoucherDraft(
                "SALES", LocalDate.of(2026, 10, 8), LocalDateTime.of(2026, 10, 8, 11, 0),
                "Airtel 199 direct", "RECHARGE_SALE", "rc_recharge#1",
                "RECHARGE_SALE:rc_recharge#1:INCOME", null, null,
                List.of(
                        VoucherLeg.debit("RCHG_PENDING", new BigDecimal("199.00")),
                        VoucherLeg.credit("FLOAT_ARTL", new BigDecimal("193.03")),
                        VoucherLeg.credit("INC_RCHG_COMM", new BigDecimal("5.97")))));

        assertEquals(3, posted.legCount());
        assertNotNull(posted.sealedAt());
    }

    // ------------------------------------------------------------------
    // Idempotency
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a second post under the same key writes nothing and returns the first")
    void duplicatePostIsIdempotent() {
        PostedVoucher first = service.post(balanced("WATER_TRIP:wt_purchase_details#1:COMPLETED"));
        PostedVoucher second = service.post(balanced("WATER_TRIP:wt_purchase_details#1:COMPLETED"));

        assertEquals(first.voucherId(), second.voucherId());
        assertFalse(first.replayed());
        assertTrue(second.replayed(), "the caller must be able to tell it was already done");
        assertEquals(2, second.legCount());
        // And nothing was duplicated.
        assertEquals(2, lineRepository.findByVoucherIdOrderByLineNo(first.voucherId()).size());
    }

    @Test
    @DisplayName("a manual voucher may leave the key null, and two of them coexist")
    void nullIdempotencyKeyIsAllowedRepeatedly() {
        // MySQL allows many NULLs in a unique index, which is the whole reason
        // uk_vch_idem can be nullable-unique: manual vouchers have no natural
        // key, while every automatic posting sets one (§5.4).
        PostedVoucher a = service.post(balanced(null));
        PostedVoucher b = service.post(balanced(null));
        assertTrue(a.voucherId() != b.voucherId());
        assertFalse(a.replayed());
        assertFalse(b.replayed());
    }

    // ------------------------------------------------------------------
    // The edge checks: a sentence, not a SIGNAL SQLSTATE '45000'
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unbalanced draft is refused with a readable message")
    void refusesUnbalanced() {
        LedgerPostingException e = assertThrows(LedgerPostingException.class,
                () -> service.post(new VoucherDraft(
                        "JOURNAL", LocalDate.of(2026, 10, 8), LocalDateTime.of(2026, 10, 8, 11, 0),
                        null, "MANUAL", null, null, null, null,
                        List.of(VoucherLeg.debit("CASH_RECHARGE", new BigDecimal("100.00")),
                                VoucherLeg.credit("INC_RCHG_COMM", new BigDecimal("99.00"))))));
        assertTrue(e.getMessage().contains("does not balance"), e.getMessage());
    }

    @Test
    @DisplayName("100.0 and 100.00 still balance")
    void scaleDoesNotBreakTheBalanceCheck() {
        // BigDecimal.equals would call these different and reject a correctly
        // balanced voucher; the service uses compareTo.
        PostedVoucher posted = service.post(new VoucherDraft(
                "JOURNAL", LocalDate.of(2026, 10, 8), LocalDateTime.of(2026, 10, 8, 11, 0),
                null, "MANUAL", null, null, null, null,
                List.of(VoucherLeg.debit("CASH_RECHARGE", new BigDecimal("100.0")),
                        VoucherLeg.credit("INC_RCHG_COMM", new BigDecimal("100.00")))));
        assertNotNull(posted.sealedAt());
    }

    @Test
    @DisplayName("a single-leg draft is refused before it reaches the trigger")
    void refusesSingleLeg() {
        LedgerPostingException e = assertThrows(LedgerPostingException.class,
                () -> service.post(new VoucherDraft(
                        "JOURNAL", LocalDate.of(2026, 10, 8), LocalDateTime.of(2026, 10, 8, 11, 0),
                        null, "MANUAL", null, null, null, null,
                        List.of(VoucherLeg.debit("CASH_RECHARGE", new BigDecimal("100.00"))))));
        assertTrue(e.getMessage().contains("at least two legs"), e.getMessage());
    }

    @Test
    @DisplayName("a two-sided or zero leg is refused")
    void refusesMalformedLegs() {
        assertTrue(assertThrows(LedgerPostingException.class, () -> service.post(draftWith(
                new VoucherLeg("CASH_RECHARGE", new BigDecimal("5.00"), new BigDecimal("5.00"),
                        null, null, null))))
                .getMessage().contains("debit or a credit"));

        assertTrue(assertThrows(LedgerPostingException.class, () -> service.post(draftWith(
                new VoucherLeg("CASH_RECHARGE", BigDecimal.ZERO, BigDecimal.ZERO,
                        null, null, null))))
                .getMessage().contains("cannot be zero"));

        assertTrue(assertThrows(LedgerPostingException.class, () -> service.post(draftWith(
                new VoucherLeg("CASH_RECHARGE", new BigDecimal("-5.00"), BigDecimal.ZERO,
                        null, null, null))))
                .getMessage().contains("cannot be negative"));
    }

    @Test
    @DisplayName("an unknown or retired account is refused")
    void refusesUnknownAccount() {
        assertTrue(assertThrows(LedgerPostingException.class,
                () -> service.post(new VoucherDraft(
                        "JOURNAL", LocalDate.of(2026, 10, 8), LocalDateTime.of(2026, 10, 8, 11, 0),
                        null, "MANUAL", null, null, null, null,
                        List.of(VoucherLeg.debit("NO_SUCH_ACCOUNT", new BigDecimal("1.00")),
                                VoucherLeg.credit("CASH_RECHARGE", new BigDecimal("1.00"))))))
                .getMessage().contains("no such account"));
    }

    @Test
    @DisplayName("a REQUIRES_PARTY account needs a customer on the leg")
    void refusesReceivableWithNoParty() {
        // AR_WATER is a receivable: without a party it cannot appear on anyone's
        // statement (FRD §8.7), so the dimension is not optional.
        assertTrue(assertThrows(LedgerPostingException.class,
                () -> service.post(new VoucherDraft(
                        "SALES", LocalDate.of(2026, 10, 8), LocalDateTime.of(2026, 10, 8, 11, 0),
                        null, "WATER_TRIP", null, null, null, null,
                        List.of(VoucherLeg.debit("AR_WATER", new BigDecimal("50.00")),
                                VoucherLeg.credit("INC_WATER_TRIP", new BigDecimal("50.00"))))))
                .getMessage().contains("requires a partyCustId"));
    }

    @Test
    @DisplayName("a machine-owned account is still postable by the service (A7)")
    void allowManualIsNotTheServicesBusiness() {
        // FLOAT_ARTL and RCHG_PENDING carry ALLOW_MANUAL = FALSE, which A7 scopes
        // to the GENERIC voucher endpoints, not to the ledger. If the service
        // enforced it, every automatic recharge posting would be blocked by the
        // flag meant to protect those accounts from hand-posting.
        PostedVoucher posted = service.post(new VoucherDraft(
                "SALES", LocalDate.of(2026, 10, 8), LocalDateTime.of(2026, 10, 8, 11, 0),
                null, "RECHARGE_SALE", null, null, null, null,
                List.of(VoucherLeg.debit("RCHG_PENDING", new BigDecimal("10.00")),
                        VoucherLeg.credit("FLOAT_ARTL", new BigDecimal("10.00")))));
        assertNotNull(posted.sealedAt());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static VoucherDraft balanced(String idempotencyKey) {
        return new VoucherDraft(
                "JOURNAL", LocalDate.of(2026, 10, 8), LocalDateTime.of(2026, 10, 8, 11, 0),
                "probe", "MANUAL", null, idempotencyKey, null, null,
                List.of(VoucherLeg.debit("CASH_RECHARGE", new BigDecimal("199.00")),
                        VoucherLeg.credit("INC_RCHG_COMM", new BigDecimal("199.00"))));
    }

    private static VoucherDraft draftWith(VoucherLeg bad) {
        return new VoucherDraft(
                "JOURNAL", LocalDate.of(2026, 10, 8), LocalDateTime.of(2026, 10, 8, 11, 0),
                null, "MANUAL", null, null, null, null,
                List.of(bad, VoucherLeg.credit("INC_RCHG_COMM", new BigDecimal("5.00"))));
    }
}
