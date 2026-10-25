package com.mangle.retailshopapp.ticket.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.mangle.retailshopapp.accounting.LedgerPostingService;
import com.mangle.retailshopapp.recharge.exception.CreditRequiresCustomerException;
import com.mangle.retailshopapp.recharge.exception.LinesDoNotSumException;
import com.mangle.retailshopapp.settlement.SettlementLine;
import com.mangle.retailshopapp.settlement.SettlementMethod;
import com.mangle.retailshopapp.ticket.domain.BookingMode;
import com.mangle.retailshopapp.ticket.exception.InvalidBookingException;
import com.mangle.retailshopapp.ticket.exception.TicketFundingMismatchException;
import com.mangle.retailshopapp.ticket.exception.UnknownFundingAccountException;
import com.mangle.retailshopapp.ticket.model.TktBooking;
import com.mangle.retailshopapp.ticket.repo.TktBookingRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one atomic write, against real MySQL.
 *
 * <p>⚠ THE CONTAINER IS NOT OPTIONAL. The rules this asserts are enforced in two
 * places on purpose — in the service so the caller gets a sentence, and in the
 * schema so nothing can get round it — and the second half only exists in a real
 * database. {@code ck_tkt_funding}, {@code uk_tkt_idem} and V23's balance trigger
 * are all behaviours H2 does not have.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TicketBookingService.class, TicketLedgerPostings.class, LedgerPostingService.class,
        TicketBookingServiceTest.FixedClock.class})
@DisplayName("Booking a ticket, against MySQL")
class TicketBookingServiceTest {

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            // Fixed so BOOKING_DTTM is predictable, but derived from today rather
            // than pinned to a date — RechargeLedgerPostingsTest records what
            // pinning one costs when a migration seeds EFFECTIVE_FROM = CURDATE().
            return Clock.fixed(LocalDate.now().atTime(11, 30).atZone(ZoneId.systemDefault())
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
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("spring.flyway.baseline-version", () -> "8");
        registry.add("spring.flyway.target", () -> "33");
        registry.add("spring.flyway.out-of-order", () -> "false");
    }

    @Autowired
    private TicketBookingService service;

    @Autowired
    private TktBookingRepository bookings;

    @Autowired
    private JdbcTemplate jdbc;

    private static SettlementLine cash(String amount) {
        return new SettlementLine(SettlementMethod.CASH, new BigDecimal(amount),
                new BigDecimal(amount), null, null, null);
    }

    private static int keyCounter = 0;

    private static String aKey() {
        return "key-" + (++keyCounter);
    }

    // ---------------------------------------------------------------- flows

    @Nested
    @DisplayName("the write")
    class Write {

        @Test
        @DisplayName("flow 1 — the row and its voucher, in one transaction")
        void flowOne() {
            var booked = service.book(BookingMode.RAIL, 1, new BigDecimal("425.00"),
                    new BigDecimal("60.00"), "BANK_SELF", List.of(cash("485.00")),
                    "Pune-Nagpur", null, aKey());

            assertNotNull(booked.booking().getBookingId());
            assertTrue(booked.booking().isShopFunded());
            assertEquals(new BigDecimal("485.00"), booked.booking().totalDue());

            // The voucher exists and is keyed on the booking, which is only
            // possible because the row was flushed first.
            assertEquals("TICKET_BOOKING:tkt_booking#" + booked.booking().getBookingId()
                            + ":CREATED",
                    jdbc.queryForObject("SELECT IDEMPOTENCY_KEY FROM acc_voucher "
                            + "WHERE VOUCHER_ID = ?", String.class, booked.voucherId()));
        }

        @Test
        @DisplayName("flow 2 — no fare, no funding account, and no bank leg")
        void flowTwo() {
            var booked = service.book(BookingMode.BUS, 2, null, new BigDecimal("120.00"),
                    null, List.of(cash("120.00")), null, null, aKey());

            assertNull(booked.booking().getFareAmount());
            assertNull(booked.booking().getFundingAccountCode());
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM acc_voucher_line WHERE VOUCHER_ID = ? "
                            + "AND ACCOUNT_CODE LIKE 'BANK_%'",
                    Integer.class, booked.voucherId()));
        }

        @Test
        @DisplayName("a blank funding account reads as absent, not as a bad account")
        void blankFundingIsAbsent() {
            // The screen sends "" when nothing is picked. Treating that as a code
            // would refuse a legitimate flow-2 booking with the wrong message.
            var booked = service.book(BookingMode.RAIL, 1, null, new BigDecimal("60.00"),
                    "  ", List.of(cash("60.00")), null, null, aKey());
            assertNull(booked.booking().getFundingAccountCode());
        }

        @Test
        @DisplayName("the credit line's customer lands on the booking")
        void creditCustomer() {
            jdbc.update("INSERT INTO rs_cust_dtls (FIRST_NAME, LAST_NAME, CONTACT_NUM) "
                    + "VALUES ('Ramesh', 'Pawar', '9960448201')");
            Integer customerId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Integer.class);

            var booked = service.book(BookingMode.RAIL, 1, null, new BigDecimal("60.00"),
                    null,
                    List.of(new SettlementLine(SettlementMethod.CREDIT, new BigDecimal("60.00"),
                            null, null, customerId, null)),
                    null, null, aKey());

            assertEquals(customerId, booked.booking().getCustId());
        }

        @Test
        @DisplayName("a cash booking stays anonymous — FRD §8.7:316")
        void cashIsAnonymous() {
            var booked = service.book(BookingMode.RAIL, 1, null, new BigDecimal("60.00"),
                    null, List.of(cash("60.00")), null, null, aKey());
            assertNull(booked.booking().getCustId());
        }
    }

    // ---------------------------------------------------------------- rules

    @Nested
    @DisplayName("the 422s, each a rule from the document")
    class Refusals {

        @Test
        @DisplayName("⚠ a fare with no funding account, and the reverse")
        void fundingMismatch() {
            assertThrows(TicketFundingMismatchException.class, () ->
                    service.book(BookingMode.RAIL, 1, new BigDecimal("425.00"),
                            new BigDecimal("60.00"), null, List.of(cash("485.00")),
                            null, null, aKey()));

            assertThrows(TicketFundingMismatchException.class, () ->
                    service.book(BookingMode.RAIL, 1, null, new BigDecimal("60.00"),
                            "BANK_SELF", List.of(cash("60.00")), null, null, aKey()));
        }

        @Test
        @DisplayName("a funding account outside the four is refused")
        void unknownFundingAccount() {
            // CASH_WATER is a real, active account — and still not one this
            // business may fund a ticket from.
            assertThrows(UnknownFundingAccountException.class, () ->
                    service.book(BookingMode.RAIL, 1, new BigDecimal("425.00"),
                            new BigDecimal("60.00"), "CASH_WATER", List.of(cash("485.00")),
                            null, null, aKey()));
        }

        @Test
        @DisplayName("⚠ a deactivated funding account is refused too")
        void inactiveFundingAccount() {
            // The half a hardcoded list could never notice. Deactivating an
            // account must stop new bookings naming it.
            jdbc.update("UPDATE acc_account SET IS_ACTIVE = FALSE WHERE ACCOUNT_CODE = 'BANK_WIFE'");
            try {
                assertThrows(UnknownFundingAccountException.class, () ->
                        service.book(BookingMode.RAIL, 1, new BigDecimal("425.00"),
                                new BigDecimal("60.00"), "BANK_WIFE", List.of(cash("485.00")),
                                null, null, aKey()));
            } finally {
                jdbc.update("UPDATE acc_account SET IS_ACTIVE = TRUE "
                        + "WHERE ACCOUNT_CODE = 'BANK_WIFE'");
            }
        }

        @Test
        @DisplayName("a passenger count below one is refused")
        void passengerCount() {
            assertThrows(InvalidBookingException.class, () ->
                    service.book(BookingMode.RAIL, 0, null, new BigDecimal("60.00"),
                            null, List.of(cash("60.00")), null, null, aKey()));
            assertThrows(InvalidBookingException.class, () ->
                    service.book(BookingMode.RAIL, null, null, new BigDecimal("60.00"),
                            null, List.of(cash("60.00")), null, null, aKey()));
        }

        @Test
        @DisplayName("negative commission is refused; zero is not")
        void commission() {
            assertThrows(InvalidBookingException.class, () ->
                    service.book(BookingMode.RAIL, 1, null, new BigDecimal("-1.00"),
                            null, List.of(cash("1.00")), null, null, aKey()));

            // E25 — 17 historical bookings earned nothing and are still flow 1.
            var booked = service.book(BookingMode.RAIL, 1, new BigDecimal("425.00"),
                    new BigDecimal("0.00"), "BANK_JANATA", List.of(cash("425.00")),
                    null, null, aKey());
            assertEquals(new BigDecimal("0.00"), booked.booking().getCommissionAmount());
        }

        @Test
        @DisplayName("the tender must sum to fare + commission, computed here and nowhere else")
        void linesMustSum() {
            assertThrows(LinesDoNotSumException.class, () ->
                    service.book(BookingMode.RAIL, 1, new BigDecimal("425.00"),
                            new BigDecimal("60.00"), "BANK_SELF", List.of(cash("400.00")),
                            null, null, aKey()));
        }

        @Test
        @DisplayName("a credit line with no customer is refused")
        void creditNeedsCustomer() {
            assertThrows(CreditRequiresCustomerException.class, () ->
                    service.book(BookingMode.RAIL, 1, null, new BigDecimal("60.00"), null,
                            List.of(new SettlementLine(SettlementMethod.CREDIT,
                                    new BigDecimal("60.00"), null, null, null, null)),
                            null, null, aKey()));
        }

        @Test
        @DisplayName("⚠ a refusal writes NOTHING — not the row, not the voucher")
        void refusalIsAtomic() {
            long bookingsBefore = bookings.count();
            Integer vouchersBefore = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM acc_voucher", Integer.class);

            assertThrows(LinesDoNotSumException.class, () ->
                    service.book(BookingMode.RAIL, 1, new BigDecimal("425.00"),
                            new BigDecimal("60.00"), "BANK_SELF", List.of(cash("1.00")),
                            null, null, aKey()));

            // A booking row with no voucher is money the ledger does not know
            // about; a voucher with no row is money from nowhere.
            assertEquals(bookingsBefore, bookings.count());
            assertEquals(vouchersBefore, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM acc_voucher", Integer.class));
        }
    }

    // ---------------------------------------------------------------- the key

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        @Test
        @DisplayName("the client's key is stored on the row")
        void keyIsStored() {
            String key = aKey();
            var booked = service.book(BookingMode.RAIL, 1, null, new BigDecimal("60.00"),
                    null, List.of(cash("60.00")), null, null, key);

            assertEquals(key, booked.booking().getIdempotencyKey());
            assertEquals(booked.booking().getBookingId(),
                    service.replayed(key).orElseThrow().getBookingId());
        }

        @Test
        @DisplayName("⚠ the same key twice is refused BY THE DATABASE, not by a pre-check")
        void duplicateKeyIsRefused() {
            String key = aKey();
            service.book(BookingMode.RAIL, 1, null, new BigDecimal("60.00"),
                    null, List.of(cash("60.00")), null, null, key);

            // uk_tkt_idem does this. A SELECT-then-insert pre-check would be a
            // race between two retries that both read "not present".
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                    service.book(BookingMode.RAIL, 1, null, new BigDecimal("60.00"),
                            null, List.of(cash("60.00")), null, null, key));
        }

        @Test
        @DisplayName("two bookings with no key coexist — uniqueness is a claim about keys given")
        void nullKeysCoexist() {
            service.book(BookingMode.RAIL, 1, null, new BigDecimal("60.00"),
                    null, List.of(cash("60.00")), null, null, null);
            service.book(BookingMode.RAIL, 1, null, new BigDecimal("60.00"),
                    null, List.of(cash("60.00")), null, null, null);
            // Reaching here is the assertion: MySQL's unique index ignores NULLs.
            assertTrue(bookings.count() >= 2);
        }

        @Test
        @DisplayName("an unseen key replays nothing")
        void unseenKey() {
            assertTrue(service.replayed("never-used").isEmpty());
        }
    }

    @Test
    @DisplayName("⚠ ck_tkt_funding still guards what the service already checked")
    void constraintIsStillTheAuthority() {
        // The service refuses this with a sentence; the database would refuse it
        // too. Both on purpose — the edge check is for the caller's benefit and
        // is not a substitute for the constraint, because the service is not the
        // only thing that can ever write this table.
        TktBooking illegal = new TktBooking();
        illegal.setBookingDttm(LocalDate.now().atTime(11, 30));
        illegal.setBookingMode(BookingMode.RAIL);
        illegal.setPassengerCount((short) 1);
        illegal.setFareAmount(new BigDecimal("425.00"));
        illegal.setCommissionAmount(new BigDecimal("60.00"));
        illegal.setFundingAccountCode(null);

        // ⚠ DataAccessException, not DataIntegrityViolationException, and the
        // difference is worth recording: a UNIQUE violation maps to
        // DataIntegrityViolationException (which is what the controller's replay
        // catch relies on and duplicateKeyIsRefused proves), but a CHECK
        // violation comes through as JpaSystemException wrapping
        // GenericJDBCException. Both are refusals; only one is catchable by the
        // replay path, and the replay path only ever needs the UNIQUE one.
        assertThrows(org.springframework.dao.DataAccessException.class, () ->
                bookings.saveAndFlush(illegal));
    }
}
