package com.mangle.retailshopapp.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S3's gate: V28, the counter's schema prerequisites, on a copy of production.
 *
 * <p>A third gate rather than assertions bolted onto the other two, because each
 * one caps Flyway at the chain its own slice ships and then asserts the state at
 * that point. {@code RechargeMigrationTest} stops at V12 and still asserts V10's
 * provisional presets; {@code LedgerMigrationTest} stops at V27. Both stay
 * correct about their own slice precisely because this one does not move them.
 *
 * <p>What makes V28 worth a gate at all is that three of its four parts are
 * corrections to something a document decided and no migration carried, so the
 * assertions here are mostly "the thing that was missing is now present":
 *
 * <ul>
 *   <li>{@code rc_recharge.SURCHARGE} exists and refuses a negative — the income
 *       account {@code INC_RCHG_SURCHARGE} has been seeded since V21 with no
 *       column to post from;
 *   <li>the two surcharge defaults are readable, because design-api.md §3.3
 *       defaults an absent surcharge to "the configured amount" and there was no
 *       configured amount;
 *   <li>the presets are the measured per-operator sets and TV has none (log
 *       §14.10), replacing V10's one-list-for-everyone proposal;
 *   <li>{@code CRED_DTTM_UNIQUE} is gone and two credit rows can share a second.
 * </ul>
 *
 * <p>That last one is asserted by WRITING, not by reading
 * {@code INFORMATION_SCHEMA}. An index that has been dropped and an index that
 * never existed look identical to a structural check, and the failure this
 * guards against — two staff settling in the same second — is a write failing.
 * Same reasoning as {@code LedgerMigrationTest}'s trigger probes.
 *
 * <p><b>Ordering is load-bearing</b>, for the reason that test spells out: the
 * two probes below write rows and clean up after themselves, so
 * {@code historyUntouched} would pass or fail on JUnit's method order without
 * {@code @Order}. The read-only assertions run first and the probes last.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("V28 applied to a production-shaped database")
class RechargeCounterMigrationTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.43")
            .withDatabaseName("recharge")
            // V23-V25's triggers are in the chain up to 28, and MySQL refuses
            // CREATE TRIGGER from a non-SUPER account while binary logging is
            // on. See LedgerMigrationTest for the full reasoning.
            .withCommand("--log-bin-trust-function-creators=1")
            .withInitScript("db/legacy-baseline.sql");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("8")
                // Still V13-V19 that must never run: V13 moves the CRDT rows out
                // of the item table, V14's CHECK breaks the legacy app's next UPI
                // write. The cap moves from 27 to 28 because V28 is this slice's
                // own migration — it does not relax what is excluded.
                .target(org.flywaydb.core.api.MigrationVersion.fromVersion("28"))
                .outOfOrder(false)
                .load()
                .migrate();
    }

    @Test
    @Order(1)
    @DisplayName("V28 applied on top of the ledger chain, and nothing from the cutover set")
    void appliedVersions() {
        assertEquals(List.of("9", "10", "11", "12", "20", "21", "22", "23", "24",
                        "25", "26", "27", "28"),
                strings("SELECT version FROM flyway_schema_history "
                        + "WHERE success = 1 AND type = 'SQL' ORDER BY installed_rank"),
                "V28 follows V27; V13-V19 must never appear here");
    }

    // ------------------------------------------------------------------
    // 1. The surcharge column
    // ------------------------------------------------------------------

    @Test
    @Order(2)
    @DisplayName("rc_recharge carries SURCHARGE, NOT NULL and defaulted to zero")
    void surchargeColumn() {
        assertEquals("decimal(38,2)", columnType("rc_recharge", "SURCHARGE"),
                "the surcharge shares AMOUNT's type: it is money on the same row "
                        + "and reaches a voucher leg in the same arithmetic");
        assertEquals("NO", columnNullable("rc_recharge", "SURCHARGE"),
                "a null surcharge would be a third state alongside zero and an "
                        + "amount, and design-api.md §3.3 has only two");
        assertTrue(checkExists("ck_rech_surcharge"), "ck_rech_surcharge should exist");
    }

    @Test
    @Order(8)
    @DisplayName("a negative surcharge is refused, and a mobile surcharge is NOT")
    void surchargeCheckBites() {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            seedOneRecharge(s, 901, "MOBILE", "RJIO", "9000000001", "199.00", "0.00");

            refused(s, "UPDATE rc_recharge SET SURCHARGE = -1.00 WHERE RECHARGE_ID = "
                            + rechargeIdOf(s, 901),
                    "a negative surcharge must be refused");

            // log §15.11 is explicit that the field stays editable and that a
            // mobile line may still carry a surcharge if staff type one — only
            // the DEFAULT is DTH-only. A CHECK keyed to RECHARGE_KIND would have
            // made the 198 historical mobile cases impossible to reproduce.
            s.executeUpdate("UPDATE rc_recharge SET SURCHARGE = 10.00 WHERE RECHARGE_ID = "
                    + rechargeIdOf(s, 901));
            assertEquals(1, count("SELECT COUNT(*) FROM rc_recharge "
                    + "WHERE RECHARGE_KIND = 'MOBILE' AND SURCHARGE = 10.00"));

            s.executeUpdate("DELETE FROM rc_recharge");
            s.executeUpdate("DELETE FROM rc_txn_details WHERE TXN_ID = 901");
            s.executeUpdate("DELETE FROM rc_txn_header WHERE TXN_ID = 901");
        } catch (SQLException e) {
            throw new AssertionError("surcharge probe failed", e);
        }
    }

    // ------------------------------------------------------------------
    // 2. The surcharge defaults
    // ------------------------------------------------------------------

    @Test
    @Order(3)
    @DisplayName("rc_setting carries both surcharge defaults, and still not ledger.opening.date")
    void surchargeSettings() {
        assertEquals("10.00", string("SELECT SETTING_VALUE FROM rc_setting "
                        + "WHERE SETTING_KEY = 'surcharge.default.tv'"),
                "Rs 10 — 95.1% of recharge-attached ANTV lines are exactly that (log §10.2)");
        assertEquals("0.00", string("SELECT SETTING_VALUE FROM rc_setting "
                        + "WHERE SETTING_KEY = 'surcharge.default.mobile'"),
                "stated rather than absent, so a zero is checkable");
        assertEquals("269,323,409", string("SELECT SETTING_VALUE FROM rc_setting "
                        + "WHERE SETTING_KEY = 'credit.ledger.customers'"),
                "the three bank pseudo-customers log §2.6 says the credit book must "
                        + "filter out — it assumed a flag that no migration ever added");
        assertEquals(9, count("SELECT COUNT(*) FROM rc_setting"),
                "V9's six plus V28's three");

        // The ledger is not open until the opening journal posts, and the
        // ABSENCE of this key is how the system knows (log §12). V28 must not
        // have invented one.
        assertFalse(exists("SELECT 1 FROM rc_setting WHERE SETTING_KEY = 'ledger.opening.date'"),
                "the opening date is written by the opening-balance call, not by a migration");
    }

    // ------------------------------------------------------------------
    // 3. The measured presets
    // ------------------------------------------------------------------

    @Test
    @Order(4)
    @DisplayName("presets are the measured per-operator sets, and TV has none")
    void presets() {
        assertEquals(12, count("SELECT COUNT(*) FROM rc_operator_preset"),
                "three operators with four amounts each, replacing V10's 45");

        assertEquals(List.of("299.00", "123.00", "349.00", "239.00"),
                strings("SELECT AMOUNT FROM rc_operator_preset "
                        + "WHERE OPERATOR_CODE = 'RJIO' ORDER BY SORT_ORDER"),
                "Jio's measured four, commonest first — 299 alone is 63% of its volume");
        assertEquals(List.of("199.00", "299.00", "349.00", "26.00"),
                strings("SELECT AMOUNT FROM rc_operator_preset "
                        + "WHERE OPERATOR_CODE = 'ARTL' ORDER BY SORT_ORDER"));
        assertEquals(List.of("147.00", "199.00", "219.00", "141.00"),
                strings("SELECT AMOUNT FROM rc_operator_preset "
                        + "WHERE OPERATOR_CODE = 'BSNL' ORDER BY SORT_ORDER"));

        // §14.10: Dish TV's commonest amount is 16% of its volume against
        // mobile's 78%, so there is no short list to offer and the screen uses a
        // stepper. V10's TV 300/500 "match nothing".
        assertEquals(0, count("SELECT COUNT(*) FROM rc_operator_preset p "
                        + "JOIN rc_operator o ON o.OPERATOR_CODE = p.OPERATOR_CODE "
                        + "WHERE o.RECHARGE_KIND = 'TV'"),
                "TV gets no presets at all");

        // Recorded as an assertion rather than a comment because it is the one
        // figure in §14.10 that the Vi merge arguably invalidated: VI inherits
        // IDEA's volume but §14.10 measured the pre-merge VI code at 23
        // recharges and gave it none. If the owner says VI should inherit
        // IDEA's set, THIS assertion is what should fail first.
        assertEquals(0, count("SELECT COUNT(*) FROM rc_operator_preset "
                        + "WHERE OPERATOR_CODE = 'VI'"),
                "VI has none per §14.10 read literally — see V28's header");

        // V20 retired IDEA and VDFN, so neither may carry presets the counter
        // could still offer.
        assertEquals(0, count("SELECT COUNT(*) FROM rc_operator_preset "
                        + "WHERE OPERATOR_CODE IN ('IDEA', 'VDFN')"));
        assertEquals(0, count("SELECT COUNT(*) FROM rc_operator_preset p "
                        + "JOIN rc_operator o ON o.OPERATOR_CODE = p.OPERATOR_CODE "
                        + "WHERE o.IS_ACTIVE = FALSE"),
                "an inactive operator must not reach the picker");
    }

    // ------------------------------------------------------------------
    // 4. The default routes
    // ------------------------------------------------------------------

    @Test
    @Order(10)
    @DisplayName("operators with no DIRECT rate default to A1TOPUP, not DIRECT")
    void defaultRoutes() {
        // Without this, 37% of recharges fail at the completion post on day one:
        // no rate row (log §8.2 refuses the income leg) AND a derived float
        // account that does not exist, because CONCAT('FLOAT_', OPERATOR_CODE)
        // only applies on the DIRECT route and the shop holds four wallets.
        for (String operator : List.of("BSNL", "DISHTV", "VIDCTV", "AIRLTV", "TATASKY", "BIGTV")) {
            assertEquals("A1TOPUP", string("SELECT DEFAULT_ROUTE_CODE FROM rc_operator "
                            + "WHERE OPERATOR_CODE = '" + operator + "'"),
                    operator + " has no direct connection (log §14.6, §2.4:86)");
        }

        // The three that DO have one keep it: both rates are seeded for them, and
        // §14.9's float consumption shows all three per-operator wallets carrying
        // real volume.
        for (String operator : List.of("ARTL", "RJIO", "VI")) {
            assertEquals("DIRECT", string("SELECT DEFAULT_ROUTE_CODE FROM rc_operator "
                            + "WHERE OPERATOR_CODE = '" + operator + "'"),
                    operator + " has a direct connection and a DIRECT rate");
        }
    }

    @Test
    @Order(11)
    @DisplayName("every selectable operator's default route has an open rate AND a float account")
    void everyDefaultIsPostable() {
        // The assertion that actually matters, because it is the one the counter
        // depends on: for each operator the picker may offer, the pair
        // (operator, default route) must resolve to both a rate and an existing
        // float account. §8.2 part 1 calls a miss "structurally near-impossible";
        // this is what makes that true rather than hoped for.
        List<String> unpostable = strings(
                "SELECT CONCAT(o.OPERATOR_CODE, '/', o.DEFAULT_ROUTE_CODE, ' ', "
                + "       CASE WHEN r.RATE_ID IS NULL THEN '[no rate]' ELSE '' END, "
                + "       CASE WHEN a.ACCOUNT_CODE IS NULL THEN '[no float account]' ELSE '' END) "
                + "  FROM rc_operator o "
                + "  LEFT JOIN acc_commission_rate r "
                + "         ON r.OPERATOR_CODE = o.OPERATOR_CODE "
                + "        AND r.ROUTE_CODE = o.DEFAULT_ROUTE_CODE "
                + "        AND r.EFFECTIVE_TO IS NULL "
                + "  LEFT JOIN acc_account a "
                + "         ON a.ACCOUNT_CODE = IF(o.DEFAULT_ROUTE_CODE = 'A1TOPUP', "
                + "                                'FLOAT_A1TOPUP', "
                + "                                CONCAT('FLOAT_', o.OPERATOR_CODE)) "
                + "        AND a.IS_ACTIVE = TRUE "
                + " WHERE o.IS_ACTIVE = TRUE "
                + "   AND o.OPERATOR_CODE NOT LIKE '%_UNKNOWN' "
                + "   AND (r.RATE_ID IS NULL OR a.ACCOUNT_CODE IS NULL)");

        assertEquals(List.of(), unpostable,
                "every operator the counter can offer must post cleanly; these cannot: "
                        + unpostable);
    }

    // ------------------------------------------------------------------
    // 5. rc_credit_req
    // ------------------------------------------------------------------

    @Test
    @Order(5)
    @DisplayName("rc_credit_req gains §4.5's three columns, nullable for history")
    void creditReqColumns() {
        for (String column : List.of("CRE_BY", "NOTE", "TXN_ID")) {
            assertEquals("YES", columnNullable("rc_credit_req", column),
                    column + " stays NULL for the 4,301 historical rows — no record "
                            + "of who extended a legacy credit exists (§8.8)");
        }
        assertEquals("mediumint", columnType("rc_credit_req", "TXN_ID"),
                "MEDIUMINT to match rc_txn_header.TXN_ID, or the FK is errno 150");
        assertTrue(fkExists("rc_credit_req", "fk_credit_creby"));
        assertTrue(fkExists("rc_credit_req", "fk_credit_txn"));
    }

    @Test
    @Order(6)
    @DisplayName("CRED_DTTM_UNIQUE is gone, and its replacement index is present")
    void creditReqIndexes() {
        assertFalse(indexExists("rc_credit_req", "CRED_DTTM_UNIQUE"),
                "dropped for good, not for the migration — §6.4");
        assertTrue(indexExists("rc_credit_req", "idx_credit_cust_dttm"),
                "the honest replacement: serves the credit book and constrains nothing");
    }

    @Test
    @Order(9)
    @DisplayName("two customers can be given credit in the same second")
    void twoCreditsOneSecond() {
        // The whole point of dropping the index, and unprovable by reading
        // INFORMATION_SCHEMA: a dropped index and an index that never existed
        // look the same there. One settle action on a two-recharge basket can
        // legitimately write more than one credit row, and the first time two
        // staff settle concurrently the old index threw DuplicateKeyException
        // from inside a transaction carrying a sale.
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO rc_credit_req (CUST_ID, CRED_DTTM, req_amt, req_type) "
                    + "VALUES (1, '2026-10-12 11:30:00', -49.00, 'DBT')");
            s.executeUpdate("INSERT INTO rc_credit_req (CUST_ID, CRED_DTTM, req_amt, req_type) "
                    + "VALUES (2, '2026-10-12 11:30:00', -99.00, 'DBT')");

            assertEquals(2, count("SELECT COUNT(*) FROM rc_credit_req "
                            + "WHERE CRED_DTTM = '2026-10-12 11:30:00'"),
                    "two different customers, one second — a basket and two staff "
                            + "both make this reachable");

            s.executeUpdate("DELETE FROM rc_credit_req WHERE CRED_DTTM = '2026-10-12 11:30:00'");
        } catch (SQLException e) {
            throw new AssertionError("concurrent-credit probe failed", e);
        }
    }

    @Test
    @Order(7)
    @DisplayName("V28 left history alone")
    void historyUntouched() {
        // The slice's hard rule (implementation-plan.md §1.2): S6 is the only
        // slice that touches historical data. The fixture's legacy rows are few,
        // so this asserts the shape rather than the production counts.
        assertEquals(0, count("SELECT COUNT(*) FROM rc_recharge"),
                "rc_recharge stays empty until V15 runs on cutover day");
        assertEquals(0, count("SELECT COUNT(*) FROM rc_txn_payment"));
        assertEquals(0, count("SELECT COUNT(*) FROM rc_credit_req WHERE CRE_BY IS NOT NULL"),
                "nothing was backfilled into the new columns");
        assertEquals(0, count("SELECT COUNT(*) FROM rc_credit_req WHERE TXN_ID IS NOT NULL"),
                "TXN_ID is back-fillable from the 2,844 CRDT rows, but that is V13's "
                        + "job on cutover day");
    }

    // ------------------------------------------------------------------
    // Helpers — the same shape as the other two gates
    // ------------------------------------------------------------------

    /**
     * rc_recharge's composite FK to rc_txn_details means a probe row needs a
     * basket and an item line first. Returns nothing; the caller reads the id
     * back through {@link #rechargeIdOf}.
     */
    private static void seedOneRecharge(Statement s, int txnId, String kind, String operator,
                                        String number, String amount, String surcharge)
            throws SQLException {
        s.executeUpdate("INSERT INTO rc_txn_header (TXN_ID, TXN_DTTM, txn_total_amt, "
                + "amt_tndred, amt_returned) VALUES (" + txnId
                + ", '2026-10-12 11:00:00', " + amount + ", " + amount + ", 0)");
        s.executeUpdate("INSERT INTO rc_txn_details (TXN_ID, TXN_FLG, AMOUNT) VALUES ("
                + txnId + ", 'MOBL', " + amount + ")");
        String seqNo = string("SELECT SEQ_NO FROM rc_txn_details WHERE TXN_ID = " + txnId);
        s.executeUpdate("INSERT INTO rc_recharge (RECHARGE_KIND, OPERATOR_CODE, ROUTE_CODE, "
                + "TARGET_NUMBER, AMOUNT, SURCHARGE, RECHARGE_DTTM, STATUS_CODE, TXN_ID, SEQ_NO) "
                + "VALUES ('" + kind + "', '" + operator + "', 'DIRECT', '" + number + "', "
                + amount + ", " + surcharge + ", '2026-10-12 11:00:00', 'AWAITING_RECHARGE', "
                + txnId + ", " + seqNo + ")");
    }

    private static String rechargeIdOf(Statement s, int txnId) {
        return string("SELECT RECHARGE_ID FROM rc_recharge WHERE TXN_ID = " + txnId);
    }

    private static void refused(Statement s, String sql, String message) {
        try {
            s.executeUpdate(sql);
            throw new AssertionError(message + " — but it was accepted: " + sql);
        } catch (SQLException expected) {
            // The refusal is the assertion.
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static String columnType(String table, String column) {
        return string("SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table
                + "' AND COLUMN_NAME = '" + column + "'")
                + typeSuffix(table, column);
    }

    /** MySQL reports decimal precision separately from DATA_TYPE. */
    private static String typeSuffix(String table, String column) {
        String type = string("SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table
                + "' AND COLUMN_NAME = '" + column + "'");
        if (!"decimal".equals(type)) {
            return "";
        }
        return "(" + string("SELECT NUMERIC_PRECISION FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table
                + "' AND COLUMN_NAME = '" + column + "'")
                + "," + string("SELECT NUMERIC_SCALE FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table
                + "' AND COLUMN_NAME = '" + column + "'") + ")";
    }

    private static String columnNullable(String table, String column) {
        return string("SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table
                + "' AND COLUMN_NAME = '" + column + "'");
    }

    private static boolean fkExists(String table, String name) {
        return exists("SELECT 1 FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND CONSTRAINT_NAME = '" + name + "' "
                + "AND CONSTRAINT_TYPE = 'FOREIGN KEY' AND TABLE_NAME = '" + table + "'");
    }

    private static boolean checkExists(String name) {
        return exists("SELECT 1 FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS "
                + "WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = '" + name + "'");
    }

    private static boolean indexExists(String table, String name) {
        return exists("SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table
                + "' AND INDEX_NAME = '" + name + "'");
    }

    private static boolean exists(String sql) {
        return !strings(sql).isEmpty();
    }

    private static long count(String sql) {
        String v = string(sql);
        return v == null ? 0L : Long.parseLong(v);
    }

    private static String string(String sql) {
        List<String> rows = strings(sql);
        return rows.isEmpty() ? null : rows.get(0);
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
}
