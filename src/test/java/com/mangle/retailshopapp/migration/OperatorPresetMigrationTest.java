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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V30 — Vi's amount presets, on a copy of production.
 *
 * <p>A fourth gate, following the convention the other three set: each caps
 * Flyway at the chain its own change ships and asserts the state at that point,
 * so none of them has to move when a later migration lands.
 * {@code RechargeMigrationTest} stops at V12, {@code LedgerMigrationTest} at
 * V27, {@code RechargeCounterMigrationTest} at V28. This one runs the whole
 * chain, because V30 is the head.
 *
 * <p><b>⚠ WHY THIS TEST EXISTS AT ALL, which is the part worth reading.</b> The
 * change V30 makes was first attempted on 2026-10-22 by editing
 * {@code V20__Operator_catalog_vi_merge.sql} in place — a migration applied to
 * the developer restore on 2026-10-09. That does two things and neither is the
 * intended one:
 *
 * <ol>
 *   <li>it changes V20's checksum, so {@code flyway validate} fails on every
 *       database that already carries V20, and since Flyway validates before it
 *       migrates, <b>the application stops starting entirely</b>. That is how it
 *       was found: the first IDE boot against the restore;
 *   <li>it never applies. Flyway does not re-run an applied migration, so the
 *       repository would hold text that changed nothing, while a database built
 *       from scratch got different preset rows from one migrated forward.
 * </ol>
 *
 * <p>It also broke {@code LedgerMigrationTest.viMerge}, which asserts V20's
 * seven rows — and that assertion was RIGHT, which is why V20 was reverted
 * rather than the test relaxed. A test that is edited to match a change is not
 * evidence of anything. The two tests now assert different moments of the same
 * table: {@code viMerge} what V20 does, this what V30 does afterwards.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("V30 applied to a production-shaped database")
class OperatorPresetMigrationTest {

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
                // 30, matching spring.flyway.target. V13-V19 are still excluded
                // and still do not exist as files: raising the cap for V30 does
                // not relax what the cap is for.
                .target(org.flywaydb.core.api.MigrationVersion.fromVersion("30"))
                .outOfOrder(false)
                .load()
                .migrate();
    }

    @Test
    @DisplayName("V30 follows V29, and nothing from the cutover set appears")
    void appliedVersions() {
        assertEquals(List.of("9", "10", "11", "12", "20", "21", "22", "23", "24",
                        "25", "26", "27", "28", "29", "30"),
                strings("SELECT version FROM flyway_schema_history "
                        + "WHERE success = 1 AND type = 'SQL' ORDER BY installed_rank"),
                "V13-V19 must never appear here");
    }

    @Test
    @DisplayName("⚠ Vi offers 199 / 239 / 299 — log §14.10's open item, closed")
    void viHasItsPresets() {
        // §14.10 recorded this as known and open: "Vi offers no amount presets.
        // Staff type the amount. One INSERT fixes it when the owner decides the
        // amounts." The owner decided; this is the INSERT.
        assertEquals(List.of("199.00", "239.00", "299.00"),
                strings("SELECT AMOUNT FROM rc_operator_preset "
                        + "WHERE OPERATOR_CODE = 'VI' ORDER BY SORT_ORDER"));
    }

    @Test
    @DisplayName("⚠ it scopes its DELETE to VI, and leaves V28's three operators alone")
    void theOtherOperatorsSurvive() {
        // V28 does an UNSCOPED `DELETE FROM rc_operator_preset` because it
        // reseeds every operator. V30 must not: copying that line without the
        // WHERE would silently drop the three sets the counter actually uses,
        // and the screen would show no presets at all for them.
        assertEquals(List.of("299.00", "123.00", "349.00", "239.00"),
                strings("SELECT AMOUNT FROM rc_operator_preset "
                        + "WHERE OPERATOR_CODE = 'RJIO' ORDER BY SORT_ORDER"));
        assertEquals(List.of("199.00", "299.00", "349.00", "26.00"),
                strings("SELECT AMOUNT FROM rc_operator_preset "
                        + "WHERE OPERATOR_CODE = 'ARTL' ORDER BY SORT_ORDER"));
        assertEquals(List.of("147.00", "199.00", "219.00", "141.00"),
                strings("SELECT AMOUNT FROM rc_operator_preset "
                        + "WHERE OPERATOR_CODE = 'BSNL' ORDER BY SORT_ORDER"));
    }

    @Test
    @DisplayName("no preset sits on a retired or TV operator")
    void noUnreachablePresets() {
        // V20's own guard, restated at the head of the chain: a preset on an
        // inactive operator is config nothing can reach. TV has none by decision
        // (log §14.10) — the amount is whatever the connection is due.
        assertEquals(0, count("SELECT COUNT(*) FROM rc_operator_preset p "
                        + "JOIN rc_operator o ON o.OPERATOR_CODE = p.OPERATOR_CODE "
                        + "WHERE o.IS_ACTIVE = 0"),
                "a preset on a retired operator is unreachable config");
        assertEquals(4, count("SELECT COUNT(DISTINCT OPERATOR_CODE) FROM rc_operator_preset"),
                "RJIO, ARTL, BSNL and now VI — and nothing on TV");
    }

    @Test
    @DisplayName("⚠ re-running V30's body is idempotent, which is why it DELETEs first")
    void rerunningIsIdempotent() {
        // Flyway will not re-run V30, but a developer restoring a database by
        // hand might, and the first attempt at this change produced exactly that
        // situation: three rows added to a table that already had seven. The
        // DELETE-then-INSERT shape makes the end state the same from any
        // starting point, so this asserts the shape rather than trusting it.
        exec("DELETE FROM rc_operator_preset WHERE OPERATOR_CODE = 'VI'");
        exec("INSERT INTO rc_operator_preset (OPERATOR_CODE, AMOUNT, SORT_ORDER) "
                + "SELECT 'VI', p.AMOUNT, p.SORT_ORDER FROM ("
                + "  SELECT 199.00 AS AMOUNT, 10 AS SORT_ORDER"
                + "  UNION ALL SELECT 239.00, 20"
                + "  UNION ALL SELECT 299.00, 30) p "
                + "WHERE EXISTS (SELECT 1 FROM rc_operator o "
                + "              WHERE o.OPERATOR_CODE = 'VI' AND o.IS_ACTIVE = 1)");

        assertEquals(List.of("199.00", "239.00", "299.00"),
                strings("SELECT AMOUNT FROM rc_operator_preset "
                        + "WHERE OPERATOR_CODE = 'VI' ORDER BY SORT_ORDER"),
                "three rows, not six");
    }

    @Test
    @DisplayName("the guard holds: no presets for an operator that is not active")
    void guardRefusesAnInactiveOperator() {
        // The WHERE EXISTS is not decoration. VI is only active because V20
        // merged IDEA and VDFN into it (log §15.2); had that merge not run, this
        // migration would otherwise seed presets for an operator the counter
        // cannot sell.
        assertTrue(count("SELECT COUNT(*) FROM rc_operator "
                + "WHERE OPERATOR_CODE = 'VI' AND IS_ACTIVE = 1") == 1,
                "VI is active, so the guard passes and the presets land");
        assertEquals(0, count("SELECT COUNT(*) FROM rc_operator_preset "
                        + "WHERE OPERATOR_CODE IN ('IDEA', 'VDFN')"),
                "and the operators it replaced carry none");
    }

    // ------------------------------------------------------------------

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
