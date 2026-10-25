package com.mangle.retailshopapp.ticket.service;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.mangle.retailshopapp.accounting.LedgerPostingService;
import com.mangle.retailshopapp.accounting.PostedVoucher;
import com.mangle.retailshopapp.settlement.SettlementLine;
import com.mangle.retailshopapp.settlement.SettlementMethod;
import com.mangle.retailshopapp.settlement.TenderPlan;
import com.mangle.retailshopapp.ticket.domain.BookingMode;
import com.mangle.retailshopapp.ticket.model.TktBooking;
import com.mangle.retailshopapp.ticket.repo.TktBookingRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E23, E24 and E25 against real MySQL, with V23's triggers live.
 *
 * <p>⚠ THE CONTAINER IS NOT OPTIONAL, for the reason
 * {@code RechargeLedgerPostingsTest} gives: V23's trigger 1 sums a voucher's legs
 * at the moment it is sealed and refuses an unbalanced one, so the whole point of
 * the leg arithmetic is a database behaviour H2 does not have. A test on H2 would
 * assert that the Java compiles.
 *
 * <p>Flyway is capped at 32 — this slice's head — so {@code tkt_booking},
 * {@code ref_booking_mode} and V32's gate have all run.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TicketLedgerPostings.class, LedgerPostingService.class})
@DisplayName("The ticket booking posting, against MySQL")
class TicketLedgerPostingsTest {

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
        registry.add("spring.flyway.target", () -> "32");
        registry.add("spring.flyway.out-of-order", () -> "false");
    }

    private static final LocalDateTime WHEN = LocalDate.now().atTime(11, 30, 0);

    @Autowired
    private TicketLedgerPostings postings;

    @Autowired
    private TktBookingRepository bookings;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------------------------------------------------------------- fixtures

    private TktBooking flowOne(String fare, String commission, String fundingAccount) {
        TktBooking b = new TktBooking();
        b.setBookingDttm(WHEN);
        b.setBookingMode(BookingMode.RAIL);
        b.setPassengerCount((short) 1);
        b.setFareAmount(new BigDecimal(fare));
        b.setCommissionAmount(new BigDecimal(commission));
        b.setFundingAccountCode(fundingAccount);
        return bookings.saveAndFlush(b);
    }

    private TktBooking flowTwo(String commission) {
        TktBooking b = new TktBooking();
        b.setBookingDttm(WHEN);
        b.setBookingMode(BookingMode.BUS);
        b.setPassengerCount((short) 2);
        b.setCommissionAmount(new BigDecimal(commission));
        return bookings.saveAndFlush(b);
    }

    private static TenderPlan cash(String amount) {
        return TenderPlan.of(
                List.of(new SettlementLine(SettlementMethod.CASH, new BigDecimal(amount),
                        new BigDecimal(amount), null, null, null)),
                new BigDecimal(amount));
    }

    /**
     * ⚠ The legacy baseline carries the customer TABLE and not one customer ROW,
     * so a credit leg has to make its own. AR_TICKET has REQUIRES_PARTY = TRUE
     * and fk_line_party references rs_cust_dtls, so this is not fixture padding
     * — without it the credit path cannot be exercised at all.
     */
    private Integer aCustomer() {
        jdbc.update("INSERT INTO rs_cust_dtls (FIRST_NAME, LAST_NAME, CONTACT_NUM) "
                + "VALUES ('Ramesh', 'Pawar', '9960448201')");
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Integer.class);
    }

    private List<String> legs(long voucherId) {
        return jdbc.queryForList(
                "SELECT CONCAT_WS('|', ACCOUNT_CODE, DR_AMOUNT, CR_AMOUNT, COALESCE(PAYMENT_METHOD, '-')) "
                        + "FROM acc_voucher_line WHERE VOUCHER_ID = ? ORDER BY ACCOUNT_CODE",
                String.class, voucherId);
    }

    // ---------------------------------------------------------------- the flows

    @Nested
    @DisplayName("E23 — flow 1, the shop paid the platform")
    class FlowOne {

        @Test
        @DisplayName("design-ledger.md §11.6's own worked example comes out exactly")
        @Transactional(propagation = Propagation.REQUIRED)
        void specExample() {
            TktBooking booking = flowOne("425.00", "60.00", "BANK_SELF");
            PostedVoucher posted = postings.post(booking, cash("485.00"), WHEN);

            assertEquals(List.of(
                            "BANK_SELF|0.00|425.00|-",
                            "CASH_TICKET|485.00|0.00|CASH",
                            "INC_TKT_COMM|0.00|60.00|-"),
                    legs(posted.voucherId()));
        }

        @Test
        @DisplayName("⚠ the fare is a pass-through and never touches income")
        @Transactional(propagation = Propagation.REQUIRED)
        void fareIsNotIncome() {
            TktBooking booking = flowOne("425.00", "60.00", "BANK_SELF");
            PostedVoucher posted = postings.post(booking, cash("485.00"), WHEN);

            // Only the commission reaches INC_TKT_COMM. Crediting the whole 485
            // would book ₹425 of someone else's money as this shop's earnings.
            assertEquals(new BigDecimal("60.00"),
                    jdbc.queryForObject(
                            "SELECT SUM(CR_AMOUNT) FROM acc_voucher_line WHERE VOUCHER_ID = ? "
                                    + "AND ACCOUNT_CODE = 'INC_TKT_COMM'",
                            BigDecimal.class, posted.voucherId()));
        }

        @Test
        @DisplayName("the funding account is the one the booking names, per booking")
        @Transactional(propagation = Propagation.REQUIRED)
        void fundingAccountIsPerBooking() {
            // log §10.6: four accounts, picked per booking, not a global setting.
            TktBooking booking = flowOne("1000.00", "60.00", "BANK_WIFE");
            PostedVoucher posted = postings.post(booking, cash("1060.00"), WHEN);

            assertTrue(legs(posted.voucherId()).contains("BANK_WIFE|0.00|1000.00|-"));
        }
    }

    @Nested
    @DisplayName("E24 — flow 2, the customer paid the platform")
    class FlowTwo {

        @Test
        @DisplayName("commission only, and no pass-through leg because none happened")
        @Transactional(propagation = Propagation.REQUIRED)
        void commissionOnly() {
            TktBooking booking = flowTwo("120.00");
            PostedVoucher posted = postings.post(booking, cash("120.00"), WHEN);

            assertEquals(List.of(
                            "CASH_TICKET|120.00|0.00|CASH",
                            "INC_TKT_COMM|0.00|120.00|-"),
                    legs(posted.voucherId()));
        }

        @Test
        @DisplayName("no bank account is touched, because the shop's money never moved")
        @Transactional(propagation = Propagation.REQUIRED)
        void noBankLeg() {
            TktBooking booking = flowTwo("60.00");
            PostedVoucher posted = postings.post(booking, cash("60.00"), WHEN);

            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM acc_voucher_line WHERE VOUCHER_ID = ? "
                            + "AND ACCOUNT_CODE LIKE 'BANK_%'",
                    Integer.class, posted.voucherId()));
        }
    }

    @Nested
    @DisplayName("E25 — a fare with no commission")
    class ZeroCommission {

        @Test
        @DisplayName("posts as flow 1 at zero margin, with no income leg at all")
        @Transactional(propagation = Propagation.REQUIRED)
        void zeroMargin() {
            // 17 historical baskets look like this. A zero leg is omitted rather
            // than posted, because ck_line_nonzero refuses a leg that moves
            // nothing — so "no income leg" is the correct shape, not a bug.
            TktBooking booking = flowOne("425.00", "0.00", "BANK_JANATA");
            PostedVoucher posted = postings.post(booking, cash("425.00"), WHEN);

            assertEquals(List.of(
                            "BANK_JANATA|0.00|425.00|-",
                            "CASH_TICKET|425.00|0.00|CASH"),
                    legs(posted.voucherId()));
        }
    }

    @Nested
    @DisplayName("the tenders land where FRD §8.6 says")
    class Tenders {

        @Test
        @DisplayName("UPI goes to UPI_CLEARING, carrying its reference")
        @Transactional(propagation = Propagation.REQUIRED)
        void upi() {
            TktBooking booking = flowTwo("60.00");
            TenderPlan tender = TenderPlan.of(
                    List.of(new SettlementLine(SettlementMethod.UPI, new BigDecimal("60.00"),
                            null, "UPI/447182003921", null, null)),
                    new BigDecimal("60.00"));

            PostedVoucher posted = postings.post(booking, tender, WHEN);
            assertTrue(legs(posted.voucherId()).contains("UPI_CLEARING|60.00|0.00|UPI"));
        }

        /**
         * ⚠ The trap {@code RechargeLedgerPostings.java:150-157} records, and the
         * one that only appears when a booking actually carries credit.
         * {@code PAYMENT_METHOD} foreign-keys {@code ref_payment_line_type}, whose
         * code is {@code CRDT}; the wire name is {@code CREDIT}. Posting
         * {@code name()} instead of {@code lineType()} fails
         * {@code fk_line_paymeth} on every credit sale — so this test exists to
         * make a credit booking actually happen.
         */
        @Test
        @DisplayName("⚠ credit goes to AR_TICKET as CRDT, not CREDIT, and names the customer")
        @Transactional(propagation = Propagation.REQUIRED)
        void credit() {
            Integer customerId = aCustomer();

            TktBooking booking = flowTwo("60.00");
            booking.setCustId(customerId);
            bookings.saveAndFlush(booking);

            TenderPlan tender = TenderPlan.of(
                    List.of(new SettlementLine(SettlementMethod.CREDIT, new BigDecimal("60.00"),
                            null, null, customerId, "will pay Friday")),
                    new BigDecimal("60.00"));

            PostedVoucher posted = postings.post(booking, tender, WHEN);

            // Both legs, because a voucher with only a debit does not balance and
            // V23's trigger 1 would have refused it. The point of this test is the
            // CRDT in the first one.
            assertEquals(List.of("AR_TICKET|60.00|0.00|CRDT", "INC_TKT_COMM|0.00|60.00|-"),
                    legs(posted.voucherId()));
            assertEquals(customerId, jdbc.queryForObject(
                    "SELECT PARTY_CUST_ID FROM acc_voucher_line WHERE VOUCHER_ID = ? "
                            + "AND ACCOUNT_CODE = 'AR_TICKET'",
                    Integer.class, posted.voucherId()));
        }

        @Test
        @DisplayName("a split across cash and credit still balances")
        @Transactional(propagation = Propagation.REQUIRED)
        void split() {
            Integer customerId = aCustomer();

            TktBooking booking = flowOne("425.00", "60.00", "BANK_SELF");
            TenderPlan tender = TenderPlan.of(
                    List.of(new SettlementLine(SettlementMethod.CASH, new BigDecimal("400.00"),
                                    new BigDecimal("400.00"), null, null, null),
                            new SettlementLine(SettlementMethod.CREDIT, new BigDecimal("85.00"),
                                    null, null, customerId, null)),
                    new BigDecimal("485.00"));

            PostedVoucher posted = postings.post(booking, tender, WHEN);

            // V23's trigger 1 would have refused this voucher outright if the
            // legs did not balance, so reaching here is itself the assertion —
            // but state it, because a reader should not have to know that.
            assertEquals(new BigDecimal("485.00"), jdbc.queryForObject(
                    "SELECT SUM(DR_AMOUNT) FROM acc_voucher_line WHERE VOUCHER_ID = ?",
                    BigDecimal.class, posted.voucherId()));
            assertEquals(new BigDecimal("485.00"), jdbc.queryForObject(
                    "SELECT SUM(CR_AMOUNT) FROM acc_voucher_line WHERE VOUCHER_ID = ?",
                    BigDecimal.class, posted.voucherId()));
        }
    }

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        @Test
        @DisplayName("the key is the booking's id, in §5.4's format")
        @Transactional(propagation = Propagation.REQUIRED)
        void keyFormat() {
            TktBooking booking = flowTwo("60.00");
            PostedVoucher posted = postings.post(booking, cash("60.00"), WHEN);

            assertEquals("TICKET_BOOKING:tkt_booking#" + booking.getBookingId() + ":CREATED",
                    jdbc.queryForObject(
                            "SELECT IDEMPOTENCY_KEY FROM acc_voucher WHERE VOUCHER_ID = ?",
                            String.class, posted.voucherId()));
        }

        @Test
        @DisplayName("⚠ posting before the row is flushed is refused, not keyed on null")
        @Transactional(propagation = Propagation.REQUIRED)
        void refusesUnflushed() {
            // The one ordering mistake the key cannot survive: every unflushed
            // booking would key as "tkt_booking#null:CREATED" and collide.
            TktBooking unsaved = new TktBooking();
            unsaved.setBookingDttm(WHEN);
            unsaved.setBookingMode(BookingMode.RAIL);
            unsaved.setPassengerCount((short) 1);
            unsaved.setCommissionAmount(new BigDecimal("60.00"));

            assertThrows(IllegalStateException.class, () ->
                    postings.post(unsaved, cash("60.00"), WHEN));
        }
    }

    @Test
    @DisplayName("⚠ MANDATORY — posting outside a transaction is refused")
    // ⚠ NOT_SUPPORTED suspends @DataJpaTest's own transaction. Without it this
    // test passes for the wrong reason: the test method IS a transaction, so
    // MANDATORY is satisfied and the assertion proves nothing.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void mandatoryPropagation() {
        // design-ledger.md §5.1's "single most valuable line": a caller that
        // forgets @Transactional throws here rather than committing the voucher
        // separately from the booking it describes. No @Transactional on this
        // test method, deliberately.
        TktBooking booking = new TktBooking();
        booking.setBookingId(999_999L);
        booking.setBookingDttm(WHEN);
        booking.setBookingMode(BookingMode.RAIL);
        booking.setPassengerCount((short) 1);
        booking.setCommissionAmount(new BigDecimal("60.00"));

        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class, () ->
                postings.post(booking, cash("60.00"), WHEN));
    }
}
