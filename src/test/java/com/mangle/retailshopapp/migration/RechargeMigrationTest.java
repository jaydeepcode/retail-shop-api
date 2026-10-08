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
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1's gate: the recharge domain migrations V9-V12 applied to a copy of
 * production, with schema assertions (implementation-plan.md:69).
 *
 * <p>Why a container and not the normal test profile: the test profile runs on
 * H2 with Flyway switched off, because these migrations are MySQL-specific —
 * {@code CONVERT TO CHARACTER SET}, composite foreign keys, {@code CHECK}
 * constraints and {@code INFORMATION_SCHEMA}-guarded dynamic SQL. H2 can run
 * none of it. design-crosscutting.md §5.2 justifies Testcontainers narrowly for
 * exactly this.
 *
 * <p>Why the container starts from a fixture rather than from V1: 13 of
 * production's tables predate Flyway's version-0 baseline and no migration
 * creates them, so a blank database cannot be built from this repo at all — V1's
 * first statement is an {@code ALTER TABLE rs_cust_dtls} that fails outright.
 * See {@code db/migration/README.md}. {@code db/legacy-baseline.sql} supplies
 * those tables in production's shape, and Flyway is baselined at version 8,
 * which is where production sits.
 *
 * <p>These assertions are deliberately in the style design-ledger.md §8.1 sets
 * out for V26: structure, not data. The reason is specific — {@code ddl-auto}
 * can substitute Hibernate's own foreign-key-less, CHECK-less DDL, and the
 * migrations' {@code IF NOT EXISTS} guards would then skip the real thing. No
 * data check can see that; only an {@code INFORMATION_SCHEMA} check can.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("V9-V12 applied to a production-shaped database")
class RechargeMigrationTest {

    /**
     * Pinned to production's own server version (8.0.43) rather than a floating
     * 8.0 tag, so a server upgrade cannot change what this gate means.
     */
    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.43")
            .withDatabaseName("recharge")
            .withInitScript("db/legacy-baseline.sql");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                // Production sits at version 8. The fixture supplies the
                // pre-Flyway tables, so V1-V8 are already represented and must
                // not re-run.
                .baselineOnMigrate(true)
                .baselineVersion("8")
                // V13 is the first cutover migration: it moves the CRDT rows out
                // of the item table, and V14 adds CHECK (amt_returned >= 0),
                // which breaks the legacy app's next UPI write. S1 must not run
                // them. Capping the target enforces that structurally, so adding
                // a V13 file later cannot quietly pull it into this slice's gate.
                .target(org.flywaydb.core.api.MigrationVersion.fromVersion("12"))
                .outOfOrder(false)
                .load()
                .migrate();
    }

    // ------------------------------------------------------------------
    // Flyway got where we expected
    // ------------------------------------------------------------------

    @Test
    @DisplayName("exactly V9-V12 applied, and nothing from the cutover set")
    void appliedVersions() {
        List<String> applied = strings(
                "SELECT version FROM flyway_schema_history "
                        + "WHERE success = 1 AND type = 'SQL' ORDER BY installed_rank");
        assertEquals(List.of("9", "10", "11", "12"), applied,
                "V9-V12 and only V9-V12 should have applied on top of the baseline");
    }

    // ------------------------------------------------------------------
    // V9 - reference tables, settings, charset
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V9 creates the six ref_* tables and rc_setting")
    void v9Tables() {
        for (String table : List.of("ref_recharge_status", "ref_payment_status",
                "ref_payment_line_type", "ref_recharge_kind", "ref_recharge_route",
                "ref_credit_req_type", "rc_setting")) {
            assertTrue(tableExists(table), table + " should exist");
            assertEquals("utf8mb4_0900_ai_ci", tableCollation(table),
                    table + " must be utf8mb4_0900_ai_ci, not a bare utf8mb4 that "
                            + "resolves to general_ci and then will not FK");
        }
    }

    @Test
    @DisplayName("the recharge statuses carry VOIDED, and COMPLETED is not terminal")
    void rechargeStatusSeeds() {
        assertEquals(6, count("SELECT COUNT(*) FROM ref_recharge_status"));
        // log §7.1: COMPLETED stopped being terminal so that a mis-marked sale
        // has a correction path which also drops it out of the day's takings.
        assertTrue(exists("SELECT 1 FROM ref_recharge_status WHERE CODE = 'VOIDED' AND IS_TERMINAL = 1"));
        assertTrue(exists("SELECT 1 FROM ref_recharge_status WHERE CODE = 'COMPLETED' AND IS_TERMINAL = 0"));
        // FAILED is deliberately the open state: "unresolved failures" is then
        // just STATUS_CODE='FAILED' with no extra IS_RESOLVED flag.
        assertTrue(exists("SELECT 1 FROM ref_recharge_status WHERE CODE = 'FAILED' AND IS_TERMINAL = 0"));
        assertEquals(3, count("SELECT COUNT(*) FROM ref_recharge_status WHERE IS_TERMINAL = 0"));
    }

    @Test
    @DisplayName("A1TOPUP exists and is active before V15 could ever run")
    void routeSeeds() {
        assertEquals(3, count("SELECT COUNT(*) FROM ref_recharge_route"));
        // log §8.5: V15 maps every SRVTE* row dated 2026-04-01 or later onto
        // A1TOPUP, so the route must already exist. The original plan had V16
        // insert it, which would have failed V15's foreign key.
        assertTrue(exists("SELECT 1 FROM ref_recharge_route WHERE CODE = 'A1TOPUP' AND IS_ACTIVE = 1"));
        // SARAVATE is seeded active on purpose; V16 retires it after its gate
        // passes. Seeding it inactive would make V16's UPDATE a silent no-op.
        assertTrue(exists("SELECT 1 FROM ref_recharge_route WHERE CODE = 'SARAVATE' AND IS_ACTIVE = 1"));
        assertTrue(exists("SELECT 1 FROM ref_recharge_route WHERE CODE = 'SARAVATE' AND RETIRED_ON IS NULL"));
    }

    @Test
    @DisplayName("the other reference seeds")
    void otherSeeds() {
        assertEquals(3, count("SELECT COUNT(*) FROM ref_payment_status"));
        assertEquals(3, count("SELECT COUNT(*) FROM ref_payment_line_type"));
        assertEquals(2, count("SELECT COUNT(*) FROM ref_recharge_kind"));
        assertEquals(2, count("SELECT COUNT(*) FROM ref_credit_req_type"));
        assertTrue(exists("SELECT 1 FROM ref_payment_line_type WHERE CODE = 'UPI'"));
    }

    @Test
    @DisplayName("rc_setting has all six seeds and not ledger.opening.date")
    void settingSeeds() {
        // Six, not the three or four earlier design sections mention:
        // float.imps.fee (log §2.5/§14.9) and change.display.threshold (§14.5)
        // were added by later sessions than the one that wrote the V9 plan.
        assertEquals(List.of("bank.default.account", "change.display.threshold",
                        "credit.warning.days", "eod.check.mode", "float.imps.fee",
                        "fy.start.month"),
                strings("SELECT SETTING_KEY FROM rc_setting ORDER BY SETTING_KEY"));

        assertEquals("BANK_JANATA", string(
                "SELECT SETTING_VALUE FROM rc_setting WHERE SETTING_KEY = 'bank.default.account'"));
        assertEquals("4", string(
                "SELECT SETTING_VALUE FROM rc_setting WHERE SETTING_KEY = 'fy.start.month'"));
        assertEquals("5.00", string(
                "SELECT SETTING_VALUE FROM rc_setting WHERE SETTING_KEY = 'float.imps.fee'"));
        assertEquals("5.00", string(
                "SELECT SETTING_VALUE FROM rc_setting WHERE SETTING_KEY = 'change.display.threshold'"));

        // log §12: the ABSENCE of this key is how the system knows the ledger
        // has not been opened yet. Seeding it would silently assert go-live.
        assertFalse(exists("SELECT 1 FROM rc_setting WHERE SETTING_KEY = 'ledger.opening.date'"),
                "ledger.opening.date must not be seeded - it is written on go-live day");
    }

    @Test
    @DisplayName("V9 converts exactly the four utf8mb3 legacy tables")
    void charsetConversion() {
        // These four were utf8mb3_general_ci in production and in the fixture.
        // The conversion is a prerequisite, not tidying: without it V12's
        // DISHTV_NO foreign key fails with errno 150 and V15's ref_company join
        // fails with errno 1267 (§8.9).
        for (String table : List.of("rc_txn_header", "rc_txn_details",
                "rc_credit_req", "rc_dishtv_dtls")) {
            assertEquals("utf8mb4_0900_ai_ci", tableCollation(table),
                    table + " should have been converted to utf8mb4 by V9");
        }
    }

    // ------------------------------------------------------------------
    // V10 - operator catalog
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V10 seeds 11 real operators plus two UNKNOWN sentinels, no SRVTE*")
    void operatorCatalog() {
        assertTrue(indexExists("rc_option_lookup_dtl", "uk_opt_type_option"),
                "rc_operator's composite FK needs this unique key; the primary key "
                        + "does not qualify because it carries rc_opt_sequence");

        // 6 MOBL + 5 TVOPR real operators + 2 sentinels.
        assertEquals(13, count("SELECT COUNT(*) FROM rc_operator"));

        // SRVTE* codes are routes in disguise, not operators: 'SRVTEA' means
        // "an Airtel recharge put through Saravate", which V15 splits into an
        // operator and a route.
        assertEquals(0, count("SELECT COUNT(*) FROM rc_operator WHERE LOOKUP_CODE LIKE 'SRVTE%'"),
                "SRVTE* must never become operators");

        // Mandatory: 8,769 recharge lines carry no operator and 386 more carry a
        // bare SRVTE, so those 9,155 rows need somewhere to land in V15.
        //
        // Asserting the CODES and not just the count is deliberate, and it is
        // what caught the VARCHAR(10) truncation: at the design's declared width
        // these became 'MOBL_UNKNO' and 'TVOPR_UNKN', the count was still 13,
        // and every foreign key still resolved. See V10's correction note.
        assertEquals(List.of("MOBL_UNKNOWN", "TVOPR_UNKNOWN"),
                strings("SELECT OPERATOR_CODE FROM rc_operator "
                        + "WHERE LOOKUP_CODE = 'UNKNOWN' ORDER BY OPERATOR_CODE"),
                "the sentinel codes must survive intact, not be truncated by a narrow column");

        // IS_ACTIVE comes from rc_opt_sw, which carries RNRL as 0 (Reliance,
        // 5 historical rows) and both sentinels as 0.
        assertTrue(exists("SELECT 1 FROM rc_operator WHERE OPERATOR_CODE = 'RNRL' AND IS_ACTIVE = 0"));
        // The sentinels must never appear in a picker: they exist only to
        // receive legacy rows that resolve to no real operator.
        assertEquals(0, count("SELECT COUNT(*) FROM rc_operator "
                + "WHERE LOOKUP_CODE = 'UNKNOWN' AND IS_ACTIVE = 1"));
        // 11 real operators minus RNRL; the two sentinels are inactive too.
        assertEquals(10, count("SELECT COUNT(*) FROM rc_operator WHERE IS_ACTIVE = 1"));

        // Every operator starts on DIRECT; V16 repoints the A1 ones after its gate.
        assertEquals(0, count("SELECT COUNT(*) FROM rc_operator WHERE DEFAULT_ROUTE_CODE <> 'DIRECT'"));

        assertEquals("MOBILE", string(
                "SELECT RECHARGE_KIND FROM rc_operator WHERE OPERATOR_CODE = 'ARTL'"));
        assertEquals("TV", string(
                "SELECT RECHARGE_KIND FROM rc_operator WHERE OPERATOR_CODE = 'DISHTV'"));
    }

    @Test
    @DisplayName("presets are seeded for active operators only")
    void presets() {
        // 7 mobile amounts x 5 active mobile operators (BSNL ARTL IDEA RJIO VDFN)
        // + 2 TV amounts x 5 active TV operators = 45. RNRL and both UNKNOWN
        // sentinels are inactive, so none of them get presets.
        assertEquals(45, count("SELECT COUNT(*) FROM rc_operator_preset"));
        assertEquals(0, count(
                "SELECT COUNT(*) FROM rc_operator_preset WHERE OPERATOR_CODE = 'RNRL'"));
        assertEquals(0, count("SELECT COUNT(*) FROM rc_operator_preset p "
                + "JOIN rc_operator o ON o.OPERATOR_CODE = p.OPERATOR_CODE "
                + "WHERE o.IS_ACTIVE = 0"));
        assertEquals(7, count(
                "SELECT COUNT(*) FROM rc_operator_preset WHERE OPERATOR_CODE = 'ARTL'"));
        assertEquals(2, count(
                "SELECT COUNT(*) FROM rc_operator_preset WHERE OPERATOR_CODE = 'DISHTV'"));
    }

    // ------------------------------------------------------------------
    // V11 - payment header extensions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V11 adds three columns, their FKs and four indexes")
    void headerExtensions() {
        assertEquals("varchar(20)", columnType("rc_txn_header", "STATUS_CODE"));
        assertEquals("NO", columnNullable("rc_txn_header", "STATUS_CODE"));
        // The DEFAULT is what backfills history in one step and what keeps the
        // legacy writer - which names none of these columns - working.
        assertEquals("SETTLED", columnDefault("rc_txn_header", "STATUS_CODE"));

        // Nullable, and it stays NULL for all 73,469 historical rows: no record
        // of who served a legacy sale exists anywhere (risk R5).
        assertEquals("YES", columnNullable("rc_txn_header", "CRE_BY"));
        assertEquals("YES", columnNullable("rc_txn_header", "CUST_ID"));

        for (String fk : List.of("fk_txnh_status", "fk_txnh_creby", "fk_txnh_cust")) {
            assertTrue(fkExists("rc_txn_header", fk), fk + " should exist");
        }
        for (String idx : List.of("idx_txnh_dttm", "idx_txnh_status_dttm",
                "idx_txnh_creby_dttm", "idx_txnh_cust")) {
            assertTrue(indexExists("rc_txn_header", idx), idx + " should exist");
        }

        // Makes RcTxnDetail's single-column @Id actually true (§8.5).
        assertTrue(indexExists("rc_txn_details", "uk_txn_details_seq"));
        assertFalse(count("SELECT NON_UNIQUE FROM INFORMATION_SCHEMA.STATISTICS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rc_txn_details' "
                + "AND INDEX_NAME = 'uk_txn_details_seq'") == 1,
                "uk_txn_details_seq must be unique");
    }

    // ------------------------------------------------------------------
    // V12 - the recharge tables
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V12 creates three tables, all empty")
    void v12TablesEmptyAndCorrect() {
        for (String table : List.of("rc_txn_payment", "rc_recharge", "rc_recharge_status_hist")) {
            assertTrue(tableExists(table), table + " should exist");
            assertEquals("utf8mb4_0900_ai_ci", tableCollation(table));
            // S1 ships these empty. Populating them is V13's and V15's job.
            assertEquals(0, count("SELECT COUNT(*) FROM " + table),
                    table + " must ship empty");
        }
    }

    @Test
    @DisplayName("the keys pointing at the legacy tables are mediumint, not int")
    void mediumintKeys() {
        // §4.4 names this as the most likely thing to make a first attempt fail:
        // MySQL rejects a foreign key whose child column type does not match the
        // parent's, and the legacy keys really are mediumint.
        assertEquals("mediumint", columnType("rc_txn_payment", "TXN_ID"));
        assertEquals("mediumint", columnType("rc_recharge", "TXN_ID"));
        assertEquals("mediumint", columnType("rc_recharge", "SEQ_NO"));
    }

    @Test
    @DisplayName("every foreign key and CHECK from §4.3 and §4.4 exists by name")
    void v12Constraints() {
        for (String fk : List.of("fk_txnpay_header", "fk_txnpay_type",
                "fk_txnpay_credit", "fk_txnpay_extby")) {
            assertTrue(fkExists("rc_txn_payment", fk), fk + " should exist");
        }
        for (String fk : List.of("fk_rech_kind", "fk_rech_operator", "fk_rech_route",
                "fk_rech_status", "fk_rech_line", "fk_rech_dishtv", "fk_rech_retry",
                "fk_rech_creby")) {
            assertTrue(fkExists("rc_recharge", fk), fk + " should exist");
        }
        for (String ck : List.of("ck_txnpay_amount", "ck_txnpay_credit",
                "ck_rech_amount", "ck_rech_tv")) {
            assertTrue(checkExists(ck), ck + " should exist");
        }
    }

    @Test
    @DisplayName("the CHECK constraints actually reject bad rows")
    void checksBite() {
        // A CHECK that exists in INFORMATION_SCHEMA but is not enforced would
        // pass every structural assertion above. MySQL only enforces CHECK from
        // 8.0.16, so this is worth proving rather than assuming.
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO rc_txn_header (TXN_DTTM, txn_total_amt, amt_tndred, amt_returned) "
                    + "VALUES ('2026-10-08 10:00:00', 100.00, 100.00, 0.00)");

            // AMOUNT > 0: payment lines are positive so they sum TO the total,
            // unlike the legacy negative CRDT rows that cancel it out.
            assertThrowsSql(s, "INSERT INTO rc_txn_payment (TXN_ID, SEQ_NO, LINE_TYPE, AMOUNT) "
                    + "VALUES (1, 1, 'CASH', -5.00)", "ck_txnpay_amount should reject a negative amount");

            // LINE_TYPE='CRDT' requires the credit-book row it wrote.
            assertThrowsSql(s, "INSERT INTO rc_txn_payment (TXN_ID, SEQ_NO, LINE_TYPE, AMOUNT) "
                    + "VALUES (1, 2, 'CRDT', 50.00)", "ck_txnpay_credit should require CRE_REQ_ID");

            // An unknown tender type must be impossible for ANY writer,
            // including hand-SQL - that is what the ref_* FK buys over an
            // app-only enum, and it is how ZINGTV and bare SRVTE got in.
            assertThrowsSql(s, "INSERT INTO rc_txn_payment (TXN_ID, SEQ_NO, LINE_TYPE, AMOUNT) "
                    + "VALUES (1, 3, 'BITCOIN', 50.00)", "fk_txnpay_type should reject an unknown tender");

            s.execute("DELETE FROM rc_txn_header WHERE TXN_ID = 1");
        } catch (SQLException e) {
            throw new AssertionError("constraint probe failed unexpectedly", e);
        }
    }

    // ------------------------------------------------------------------
    // Re-runnability
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V9 and V10 are idempotent when re-executed by hand")
    void guardsHold() {
        // Flyway will not re-run an applied migration, so the IF NOT EXISTS and
        // INFORMATION_SCHEMA guards are never exercised by a normal migrate.
        // They matter when a half-applied migration is re-run after a failure -
        // MySQL DDL is not transactional, so that is a real situation - and an
        // unexercised guard is a guard nobody has tested.
        assertDoesNotThrow(() -> runScript("db/migration/V9__Recharge_reference_tables.sql"),
                "V9 should be safely re-runnable");
        assertDoesNotThrow(() -> runScript("db/migration/V10__Operator_catalog.sql"),
                "V10 should be safely re-runnable");

        // And re-running changed nothing.
        assertEquals(6, count("SELECT COUNT(*) FROM rc_setting"));
        assertEquals(13, count("SELECT COUNT(*) FROM rc_operator"));
        assertEquals(6, count("SELECT COUNT(*) FROM ref_recharge_status"));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static void assertThrowsSql(Statement s, String sql, String message) {
        try {
            s.execute(sql);
            throw new AssertionError(message);
        } catch (SQLException expected) {
            // the constraint fired, which is the point
        }
    }

    private static void runScript(String resource) throws Exception {
        String body;
        try (var in = RechargeMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing resource: " + resource);
            }
            body = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        try (Connection c = connect(); Statement s = c.createStatement()) {
            for (String stmt : splitStatements(stripComments(body))) {
                s.execute(stmt);
            }
        }
    }

    /**
     * Splits a migration into statements on semicolons that are not inside a
     * string literal.
     *
     * <p>It cannot be a line-based split: the repo's dynamic-SQL idiom puts
     * three statements on one line ({@code PREPARE stmt FROM @sql; EXECUTE stmt;
     * DEALLOCATE PREPARE stmt;}), and splitting on semicolon-newline hands MySQL
     * a fragment.
     *
     * <p>It cannot be a naive split on every semicolon either — V9 seeds the
     * description {@code 'Sale should not exist; reversed'}. Tracking quote state
     * is the difference between a harness that works and one that silently
     * depends on nobody ever writing a semicolon in a literal.
     */
    private static List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '\'') {
                // '' inside a literal is an escaped quote, not a close.
                if (inString && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    current.append("''");
                    i++;
                    continue;
                }
                inString = !inString;
                current.append(ch);
            } else if (ch == ';' && !inString) {
                addIfNotBlank(out, current);
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        addIfNotBlank(out, current);
        return out;
    }

    private static void addIfNotBlank(List<String> out, CharSequence candidate) {
        String trimmed = candidate.toString().trim();
        if (!trimmed.isEmpty()) {
            out.add(trimmed);
        }
    }

    /** Drops whole-line {@code --} comments, which are all this repo's SQL uses. */
    private static String stripComments(String sql) {
        StringBuilder out = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (!line.trim().startsWith("--")) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static boolean tableExists(String table) {
        return exists("SELECT 1 FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "'");
    }

    private static String tableCollation(String table) {
        return string("SELECT TABLE_COLLATION FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "'");
    }

    private static String columnType(String table, String column) {
        return string("SELECT COLUMN_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table
                + "' AND COLUMN_NAME = '" + column + "'");
    }

    private static String columnNullable(String table, String column) {
        return string("SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table
                + "' AND COLUMN_NAME = '" + column + "'");
    }

    private static String columnDefault(String table, String column) {
        return string("SELECT COLUMN_DEFAULT FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table
                + "' AND COLUMN_NAME = '" + column + "'");
    }

    private static boolean fkExists(String table, String name) {
        return exists("SELECT 1 FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table
                + "' AND CONSTRAINT_NAME = '" + name + "' AND CONSTRAINT_TYPE = 'FOREIGN KEY'");
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
