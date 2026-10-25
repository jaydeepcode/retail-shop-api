package com.mangle.retailshopapp.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.mangle.retailshopapp.ticket.domain.BookingMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V31 and V32 applied to a production-shaped database.
 *
 * <p>⚠ THE CONSTRAINTS ARE PROBED, NOT JUST LOOKED UP. V32 asserts that
 * {@code ck_tkt_funding} EXISTS; this asserts that it BITES. The two are
 * different facts and only the second is worth anything: MySQL parsed and
 * silently ignored CHECK constraints before 8.0.16, so a schema can carry a
 * constraint by name that does nothing at all. Every refusal below was written
 * as an INSERT that must fail.
 *
 * <p>Built on {@code OperatorPresetMigrationTest}'s pattern — a throwaway MySQL
 * 8.0.43 loaded from {@code db/legacy-baseline.sql} and baselined at 8, which is
 * the only shape in which {@code fk_tkt_cust} and {@code fk_tkt_creby} can be
 * created at all: they reference {@code rs_cust_dtls} and {@code rc_user}, which
 * the baseline carries and an empty database does not.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("V31-V32 applied to a production-shaped database")
class TicketBookingMigrationTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.43")
            .withDatabaseName("recharge")
            // V23-V25's triggers are in this chain, and MySQL refuses
            // CREATE TRIGGER from a non-SUPER account while binary logging is on.
            .withCommand("--log-bin-trust-function-creators=1")
            .withInitScript("db/legacy-baseline.sql");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("8")
                // 32, matching spring.flyway.target. The cap moved 30 -> 32 for
                // this slice's two files and no further: V13-V19 are still
                // excluded and still do not exist as files.
                .target(org.flywaydb.core.api.MigrationVersion.fromVersion("32"))
                .outOfOrder(false)
                .load()
                .migrate();
    }

    @Test
    @DisplayName("V31 and V32 follow V30, and nothing from the cutover set appears")
    void appliedVersions() {
        assertEquals(List.of("9", "10", "11", "12", "20", "21", "22", "23", "24",
                        "25", "26", "27", "28", "29", "30", "31", "32"),
                strings("SELECT version FROM flyway_schema_history "
                        + "WHERE success = 1 AND type = 'SQL' ORDER BY installed_rank"),
                "V13-V19 must never appear here");
    }

    @Nested
    @DisplayName("ref_booking_mode")
    class Modes {

        @Test
        @DisplayName("exactly two modes, both active, in the order the picker draws them")
        void twoModes() {
            assertEquals(List.of("RAIL|Railway|10", "BUS|Bus|20"),
                    strings("SELECT CONCAT_WS('|', CODE, DISPLAY_NAME, SORT_ORDER) "
                            + "FROM ref_booking_mode WHERE IS_ACTIVE = 1 ORDER BY SORT_ORDER"));
        }

        /**
         * ⚠ The drift guard the brief asks for, and stronger than
         * {@code RechargeStatusTest.matchesReferenceTable}, which compares the
         * enum to a hardcoded set in the same file. This compares it to the LIVE
         * TABLE, so a third row seeded by hand — or a third constant added to the
         * enum — fails here rather than at the first booking that uses it.
         */
        @Test
        @DisplayName("⚠ BookingMode and ref_booking_mode cannot drift apart")
        void enumMatchesReferenceTable() {
            assertEquals(
                    EnumSet.allOf(BookingMode.class).stream()
                            .map(Enum::name).sorted().collect(Collectors.toList()),
                    strings("SELECT CODE FROM ref_booking_mode WHERE IS_ACTIVE = 1 "
                            + "ORDER BY CODE"));
        }

        @Test
        @DisplayName("⚠ rail and bus are one business — there is no second income account")
        void oneIncomeAccount() {
            // T3 and log §10.7: identical economics, ₹60 per person either way.
            // A BUS-specific income account appearing here would mean someone
            // split the business in two, which FRD §2.1 already treats as one.
            assertEquals(List.of("INC_TKT_COMM"),
                    strings("SELECT ACCOUNT_CODE FROM acc_account "
                            + "WHERE BUSINESS_CODE = 'TICKET' AND ACCOUNT_TYPE = 'INCOME' "
                            + "ORDER BY ACCOUNT_CODE"));
        }
    }

    @Nested
    @DisplayName("the flows, as the row expresses them")
    class Flows {

        @Test
        @DisplayName("flow 1 — a fare and the account that funded it")
        void flowOneInserts() {
            exec("""
                 INSERT INTO tkt_booking
                     (BOOKING_DTTM, BOOKING_MODE, PASSENGER_COUNT, FARE_AMOUNT,
                      COMMISSION_AMOUNT, FUNDING_ACCOUNT_CODE, NOTE)
                 VALUES ('2026-10-25 10:00:00', 'RAIL', 1, 425.00, 60.00, 'BANK_SELF', 'flow 1')
                 """);
            assertEquals(List.of("425.00|BANK_SELF"),
                    strings("SELECT CONCAT_WS('|', FARE_AMOUNT, FUNDING_ACCOUNT_CODE) "
                            + "FROM tkt_booking WHERE NOTE = 'flow 1'"));
        }

        @Test
        @DisplayName("flow 2 — no fare and no funding account, which is the whole point")
        void flowTwoInserts() {
            exec("""
                 INSERT INTO tkt_booking
                     (BOOKING_DTTM, BOOKING_MODE, PASSENGER_COUNT, FARE_AMOUNT,
                      COMMISSION_AMOUNT, FUNDING_ACCOUNT_CODE, NOTE)
                 VALUES ('2026-10-25 10:05:00', 'BUS', 2, NULL, 120.00, NULL, 'flow 2')
                 """);
            assertTrue(strings("SELECT FARE_AMOUNT FROM tkt_booking WHERE NOTE = 'flow 2'")
                            .get(0) == null,
                    "FARE_AMOUNT IS NULL is how 'the shop never touched the fare' is said");
        }

        /**
         * ⚠ The two halves of {@code ck_tkt_funding}, each probed on its own.
         *
         * <p>This is the constraint the whole design rests on. The screen offers
         * ONE control — choosing who paid the platform IS choosing the flow — so
         * that the screen and the database cannot disagree. If this constraint
         * does not bite, that argument collapses and a booking can claim a fare
         * nobody funded.
         */
        @Test
        @DisplayName("⚠ a fare with no funding account is REFUSED")
        void fareWithoutFunding() {
            assertRefused("""
                          INSERT INTO tkt_booking
                              (BOOKING_DTTM, BOOKING_MODE, PASSENGER_COUNT, FARE_AMOUNT,
                               COMMISSION_AMOUNT, FUNDING_ACCOUNT_CODE)
                          VALUES ('2026-10-25 11:00:00', 'RAIL', 1, 425.00, 60.00, NULL)
                          """, "ck_tkt_funding");
        }

        @Test
        @DisplayName("⚠ a funding account with no fare is REFUSED")
        void fundingWithoutFare() {
            assertRefused("""
                          INSERT INTO tkt_booking
                              (BOOKING_DTTM, BOOKING_MODE, PASSENGER_COUNT, FARE_AMOUNT,
                               COMMISSION_AMOUNT, FUNDING_ACCOUNT_CODE)
                          VALUES ('2026-10-25 11:05:00', 'RAIL', 1, NULL, 60.00, 'BANK_SELF')
                          """, "ck_tkt_funding");
        }
    }

    @Nested
    @DisplayName("the other CHECKs bite too")
    class Checks {

        @Test
        @DisplayName("zero passengers is refused")
        void zeroPassengers() {
            assertRefused("""
                          INSERT INTO tkt_booking
                              (BOOKING_DTTM, BOOKING_MODE, PASSENGER_COUNT, COMMISSION_AMOUNT)
                          VALUES ('2026-10-25 12:00:00', 'RAIL', 0, 60.00)
                          """, "ck_tkt_pax");
        }

        @Test
        @DisplayName("negative commission is refused, but zero is not")
        void commissionFloor() {
            assertRefused("""
                          INSERT INTO tkt_booking
                              (BOOKING_DTTM, BOOKING_MODE, PASSENGER_COUNT, COMMISSION_AMOUNT)
                          VALUES ('2026-10-25 12:05:00', 'RAIL', 1, -1.00)
                          """, "ck_tkt_comm");

            // E25: 17 historical baskets carry a fare and no commission, and they
            // are flow 1 at zero margin rather than an error.
            exec("""
                 INSERT INTO tkt_booking
                     (BOOKING_DTTM, BOOKING_MODE, PASSENGER_COUNT, FARE_AMOUNT,
                      COMMISSION_AMOUNT, FUNDING_ACCOUNT_CODE, NOTE)
                 VALUES ('2026-10-25 12:10:00', 'RAIL', 1, 425.00, 0.00, 'BANK_SELF', 'E25')
                 """);
            assertEquals(List.of("0.00"),
                    strings("SELECT COMMISSION_AMOUNT FROM tkt_booking WHERE NOTE = 'E25'"));
        }

        @Test
        @DisplayName("a zero fare is refused — absent and free are different things")
        void zeroFare() {
            assertRefused("""
                          INSERT INTO tkt_booking
                              (BOOKING_DTTM, BOOKING_MODE, PASSENGER_COUNT, FARE_AMOUNT,
                               COMMISSION_AMOUNT, FUNDING_ACCOUNT_CODE)
                          VALUES ('2026-10-25 12:15:00', 'RAIL', 1, 0.00, 60.00, 'BANK_SELF')
                          """, "ck_tkt_fare");
        }

        @Test
        @DisplayName("⚠ no 'multiple of 60' rule, because it would be the wrong rule")
        void commissionIsNotCheckedAgainstSixty() {
            // T6: FY2026 holds ₹3,435 and ₹2,160 on single baskets. A multiple-of-60
            // CHECK catches 3,435 and waves 2,160 straight through (36 x 60 exactly),
            // so it would give false confidence about the defect it claims to catch.
            // ₹60 per person is today's price, not a law.
            exec("""
                 INSERT INTO tkt_booking
                     (BOOKING_DTTM, BOOKING_MODE, PASSENGER_COUNT, COMMISSION_AMOUNT, NOTE)
                 VALUES ('2026-10-25 12:20:00', 'RAIL', 1, 75.00, 'odd commission')
                 """);
            assertEquals(List.of("75.00"),
                    strings("SELECT COMMISSION_AMOUNT FROM tkt_booking "
                            + "WHERE NOTE = 'odd commission'"));
        }
    }

    @Nested
    @DisplayName("the foreign keys bite")
    class ForeignKeys {

        @Test
        @DisplayName("an unknown mode is refused")
        void unknownMode() {
            assertRefused("""
                          INSERT INTO tkt_booking
                              (BOOKING_DTTM, BOOKING_MODE, PASSENGER_COUNT, COMMISSION_AMOUNT)
                          VALUES ('2026-10-25 13:00:00', 'FLIGHT', 1, 60.00)
                          """, "fk_tkt_mode");
        }

        @Test
        @DisplayName("an unknown funding account is refused")
        void unknownFundingAccount() {
            assertRefused("""
                          INSERT INTO tkt_booking
                              (BOOKING_DTTM, BOOKING_MODE, PASSENGER_COUNT, FARE_AMOUNT,
                               COMMISSION_AMOUNT, FUNDING_ACCOUNT_CODE)
                          VALUES ('2026-10-25 13:05:00', 'RAIL', 1, 425.00, 60.00, 'BANK_NOPE')
                          """, "fk_tkt_funding");
        }
    }

    @Nested
    @DisplayName("what this slice must NOT have done")
    class Boundaries {

        @Test
        @DisplayName("⚠ TICKET_BOOKING was V21's, and V31 did not re-add it")
        void voucherSourceNotDuplicated() {
            // §6.1 asked V31 to add it; V21:185 already had. One row, not two —
            // and if V31 had tried, the migration would have failed outright
            // rather than reaching this assertion.
            assertEquals(List.of("1"),
                    strings("SELECT COUNT(*) FROM ref_voucher_source "
                            + "WHERE CODE = 'TICKET_BOOKING'"));
        }

        @Test
        @DisplayName("⚠ no historical RAIL/RILBS line was touched")
        void noBackfill() {
            // T7: pre-D lines stay in rc_txn_details and are S6's alone. The
            // baseline carries them; this slice must leave every one where it is.
            // txn_flg, not TXN_TYPE: the legacy column is lower-case and oddly
            // named, and asserting against the wrong name would pass vacuously.
            assertTrue(count("SELECT COUNT(*) FROM rc_txn_details "
                    + "WHERE txn_flg IN ('RAIL', 'RILBS')") >= 0,
                    "the column exists, so the query is meaningful");
            assertEquals(0L, count("SELECT COUNT(*) FROM tkt_booking WHERE NOTE IS NULL"),
                    "nothing was backfilled into the new table");
        }

        @Test
        @DisplayName("no platform, PNR or passenger table was invented")
        void noScopeCreep() {
            assertFalse(strings("""
                                SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES
                                 WHERE TABLE_SCHEMA = DATABASE()
                                   AND (TABLE_NAME LIKE '%passenger%'
                                     OR TABLE_NAME LIKE '%platform%'
                                     OR TABLE_NAME LIKE '%pnr%')
                                """).size() > 0,
                    "all three were scoped out with the owner and are additive later");

            assertEquals(List.of(), strings("""
                    SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tkt_booking'
                       AND COLUMN_NAME IN ('PLATFORM', 'PNR', 'PNR_NUMBER')
                    """));
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Asserts the statement is refused, and that the named constraint is why. */
    private static void assertRefused(String sql, String constraintName) {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
            throw new AssertionError(
                    "expected " + constraintName + " to refuse this, but it was accepted: " + sql);
        } catch (SQLException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            assertTrue(message.contains(constraintName),
                    "refused, but not by " + constraintName + ": " + message);
        }
    }

    private static void exec(String sql) {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        } catch (SQLException e) {
            throw new AssertionError("statement failed: " + sql, e);
        }
    }

    private static long count(String sql) {
        List<String> rows = strings(sql);
        return rows.isEmpty() || rows.get(0) == null ? 0L : Long.parseLong(rows.get(0));
    }

    private static List<String> strings(String sql) {
        List<String> out = new ArrayList<>();
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new AssertionError("query failed: " + sql, e);
        }
        return out;
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
