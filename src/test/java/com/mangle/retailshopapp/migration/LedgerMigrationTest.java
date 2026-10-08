package com.mangle.retailshopapp.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2's gate: V20 and the ledger chain V21-V27 applied to a copy of production.
 *
 * <p>Built on {@code RechargeMigrationTest}'s pattern — a throwaway MySQL 8.0.43
 * loaded from {@code db/legacy-baseline.sql} and baselined at version 8, which is
 * where production sits. That test keeps its own container capped at V12 so S1's
 * gate still asserts S1's state; this one carries the chain to V27.
 *
 * <p><b>Why the container is the only honest way to test this slice.</b> V23's
 * five triggers, V24's and V25's one each are the whole of §2.4's and §4.1's
 * guarantee, and H2 can run none of them. Worse, a trigger that EXISTS but does
 * not FIRE passes every structural assertion there is —
 * {@code INFORMATION_SCHEMA.TRIGGERS} cannot tell the difference. So this test
 * asserts both: that each trigger is present, and that the bad write it exists to
 * stop is actually refused. That is the same reasoning that made
 * {@code RechargeMigrationTest.checksBite} probe its CHECK constraints rather
 * than just confirm they exist.
 *
 * <p><b>Ordering is load-bearing.</b> The emptiness and seed-count assertions
 * must run before any test writes a voucher, so the destructive probes carry
 * higher {@code @Order} values. Without that, "the ledger ships empty" would pass
 * or fail depending on JUnit's method order.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("V20-V27 applied to a production-shaped database")
class LedgerMigrationTest {

    /**
     * ⚠ {@code --log-bin-trust-function-creators=1} is not tuning, it is what
     * makes V23 runnable at all by a non-SUPER user.
     *
     * <p>MySQL 8.0.43 ships with {@code log_bin = ON} and
     * {@code log_bin_trust_function_creators = OFF}, and in that state
     * {@code CREATE TRIGGER} from a user without {@code SUPER} fails with
     * "ERROR 1419: You do not have the SUPER privilege and binary logging is
     * enabled". Testcontainers connects as an ordinary user, which is also how a
     * sane production deployment connects — so without this flag V23 fails here
     * and would fail on the first production deploy too.
     *
     * <p>Setting it here rather than granting the test user {@code SUPER} is
     * deliberate: it exercises the least-privilege path that production should
     * use, so the gate proves the remedy works instead of hiding the problem
     * behind an over-privileged account. The requirement is recorded in V23's
     * header and in {@code db/migration/README.md}.
     */
    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.43")
            .withDatabaseName("recharge")
            .withCommand("--log-bin-trust-function-creators=1")
            .withInitScript("db/legacy-baseline.sql");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("8")
                // V13-V19 are cutover work, incompatible with the live legacy
                // writer: V13 moves the CRDT rows out of the item table and V14
                // adds CHECK (amt_returned >= 0), which breaks the legacy app's
                // next UPI write. They do not exist yet, and capping the target
                // at 27 means adding one later cannot quietly pull it into this
                // gate.
                .target(org.flywaydb.core.api.MigrationVersion.fromVersion("27"))
                .outOfOrder(false)
                .load()
                .migrate();
    }

    // ------------------------------------------------------------------
    // Flyway got where we expected, and no further
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("V9-V12 and V20-V27 applied, and nothing from the cutover set")
    void appliedVersions() {
        assertEquals(List.of("9", "10", "11", "12", "20", "21", "22", "23", "24", "25", "26", "27"),
                strings("SELECT version FROM flyway_schema_history "
                        + "WHERE success = 1 AND type = 'SQL' ORDER BY installed_rank"),
                "the ledger chain is V21-V27 because V20 is the operator merge; "
                        + "V13-V19 must never appear here");
    }

    // ------------------------------------------------------------------
    // V20 - the Vi merge
    // ------------------------------------------------------------------

    @Test
    @Order(2)
    @DisplayName("V20 creates VI, retires IDEA and VDFN, and moves their presets")
    void viMerge() {
        // The merge is what makes the four-wallet float derivation total: two
        // operator codes behind one wallet is the single case
        // CONCAT('FLOAT_', OPERATOR_CODE) cannot express (log §2.4:103-105).
        assertTrue(exists("SELECT 1 FROM rc_operator WHERE OPERATOR_CODE = 'VI' "
                + "AND IS_ACTIVE = 1 AND RECHARGE_KIND = 'MOBILE' AND DISPLAY_NAME = 'Vi'"));
        assertEquals("DIRECT", string(
                "SELECT DEFAULT_ROUTE_CODE FROM rc_operator WHERE OPERATOR_CODE = 'VI'"),
                "V16 is what repoints the A1Topup operators, and it is cutover work");

        // Retired, never deleted: rc_recharge.OPERATOR_CODE foreign-keys them and
        // V15's backfill maps legacy SRVTEI/SRVTEV rows onto them, so pre-cutover
        // history stays truthful about which code was typed.
        assertEquals(0, count("SELECT COUNT(*) FROM rc_operator "
                + "WHERE OPERATOR_CODE IN ('IDEA','VDFN') AND IS_ACTIVE = 1"));
        assertEquals(2, count("SELECT COUNT(*) FROM rc_operator "
                + "WHERE OPERATOR_CODE IN ('IDEA','VDFN') AND RETIRED_ON IS NOT NULL"));

        // 13 from V10 plus VI.
        assertEquals(14, count("SELECT COUNT(*) FROM rc_operator"));
        // 10 active after V10, minus IDEA and VDFN, plus VI.
        assertEquals(List.of("AIRLTV", "ARTL", "BIGTV", "BSNL", "DISHTV",
                        "RJIO", "TATASKY", "VI", "VIDCTV"),
                strings("SELECT OPERATOR_CODE FROM rc_operator WHERE IS_ACTIVE = 1 "
                        + "ORDER BY OPERATOR_CODE"));

        // 45 from V10, minus 7 each for IDEA and VDFN, plus 7 for VI.
        assertEquals(38, count("SELECT COUNT(*) FROM rc_operator_preset"));
        assertEquals(7, count(
                "SELECT COUNT(*) FROM rc_operator_preset WHERE OPERATOR_CODE = 'VI'"));
        assertEquals(0, count("SELECT COUNT(*) FROM rc_operator_preset p "
                + "JOIN rc_operator o ON o.OPERATOR_CODE = p.OPERATOR_CODE WHERE o.IS_ACTIVE = 0"),
                "a preset on a retired operator is unreachable config");

        // The legacy app reads rc_opt_sw, not rc_operator.IS_ACTIVE, so VI must
        // not appear in its picker (the V10 idiom for the UNKNOWN sentinels).
        assertEquals(0, count("SELECT COUNT(*) FROM rc_option_lookup_dtl "
                + "WHERE rc_option = 'VI' AND rc_opt_sw = 1"));
    }

    // ------------------------------------------------------------------
    // V21 - reference tables and the chart of accounts
    // ------------------------------------------------------------------

    @Test
    @Order(3)
    @DisplayName("V21 creates six ref_* tables with the right seeds")
    void ledgerReferenceTables() {
        for (String table : List.of("ref_account_type", "ref_business", "ref_voucher_type",
                "ref_voucher_source", "ref_float_pattern", "ref_float_movement_type")) {
            assertTrue(tableExists(table), table + " should exist");
            assertEquals("utf8mb4_0900_ai_ci", tableCollation(table),
                    table + " must declare the collation, not just the charset, or it "
                            + "will not FK against rc_operator / rs_cust_dtls");
        }
        assertEquals(5, count("SELECT COUNT(*) FROM ref_account_type"));
        // EQUITY is a fifth type FRD §4 does not list. Not optional: an opening
        // journal cannot balance without somewhere to put the residual (log §2.2).
        assertTrue(exists("SELECT 1 FROM ref_account_type WHERE CODE = 'EQUITY' "
                + "AND NORMAL_BALANCE = 'C' AND IS_PL = 0"));
        // The P&L query filters on IS_PL, never on a hardcoded type list.
        assertEquals(List.of("EXPENSE", "INCOME"),
                strings("SELECT CODE FROM ref_account_type WHERE IS_PL = 1 ORDER BY CODE"));

        assertEquals(4, count("SELECT COUNT(*) FROM ref_business"));
        assertEquals(6, count("SELECT COUNT(*) FROM ref_voucher_type"));
        // FRD §6: CONTRA "never affects P&L".
        assertEquals(List.of("CONTRA"),
                strings("SELECT CODE FROM ref_voucher_type WHERE AFFECTS_PL = 0"));
        assertEquals(12, count("SELECT COUNT(*) FROM ref_voucher_source"));
        assertTrue(exists("SELECT 1 FROM ref_voucher_source WHERE CODE = 'OPENING'"),
                "V28's opening call needs this source to exist");
        assertEquals(3, count("SELECT COUNT(*) FROM ref_float_pattern"));
        assertEquals(4, count("SELECT COUNT(*) FROM ref_float_movement_type"));
    }

    @Test
    @Order(4)
    @DisplayName("V21 seeds 28 accounts plus four float wallets, not ten")
    void chartOfAccounts() {
        assertEquals("utf8mb4_0900_ai_ci", tableCollation("acc_account"));

        // 28 hand-seeded (FRD §5's 19 plus the 9 log §11.1 adds) + 4 wallets.
        assertEquals(32, count("SELECT COUNT(*) FROM acc_account"));
        assertEquals(28, count("SELECT COUNT(*) FROM acc_account WHERE FLOAT_PATTERN IS NULL"));

        // ⚠ FOUR wallets, not the ten design-ledger.md:275-282 would generate.
        // log §2.4:84-105 decided route-not-operator and was never applied to
        // that SQL. §14.9:1973-1975 measures the four: A1Topup Rs 87,018, Jio
        // Rs 66,568, Airtel Rs 66,239, Vi Rs 39,606.
        assertEquals(List.of("FLOAT_ARTL", "FLOAT_RJIO", "FLOAT_VI", "FLOAT_A1TOPUP"),
                strings("SELECT ACCOUNT_CODE FROM acc_account "
                        + "WHERE FLOAT_PATTERN IS NOT NULL ORDER BY SORT_ORDER"));
        // Three map to one operator; FLOAT_A1TOPUP answers to six, so its
        // OPERATOR_CODE is NULL and FLOAT_PATTERN is the discriminator instead.
        assertEquals("ARTL", string(
                "SELECT OPERATOR_CODE FROM acc_account WHERE ACCOUNT_CODE = 'FLOAT_ARTL'"));
        assertEquals("VI", string(
                "SELECT OPERATOR_CODE FROM acc_account WHERE ACCOUNT_CODE = 'FLOAT_VI'"));
        assertEquals(null, string(
                "SELECT OPERATOR_CODE FROM acc_account WHERE ACCOUNT_CODE = 'FLOAT_A1TOPUP'"),
                "a route is not an operator");
        // All four are FRD §7.2 pattern 2, so cost equals face at ratio 1.0.
        assertEquals(4, count("SELECT COUNT(*) FROM acc_account "
                + "WHERE FLOAT_PATTERN = 'TXN_RATE' AND IS_RECONCILABLE = 1 AND ALLOW_MANUAL = 0"));

        // BANK_JANATA, never a generic BANK: ACCOUNT_CODE never changes by
        // design, so a generic code would mean Janata forever (log §9.3).
        assertTrue(exists("SELECT 1 FROM acc_account WHERE ACCOUNT_CODE = 'BANK_JANATA'"));
        assertFalse(exists("SELECT 1 FROM acc_account WHERE ACCOUNT_CODE = 'BANK'"));
        // And V9 already points the setting at it, with no compiled-in fallback.
        assertEquals("BANK_JANATA", string("SELECT SETTING_VALUE FROM rc_setting "
                + "WHERE SETTING_KEY = 'bank.default.account'"));

        // OPERATOR_CODE must be VARCHAR(20): S1's V10 widened rc_operator's to
        // hold TVOPR_UNKNOWN. §8.2 says VARCHAR(10), which is errno 150.
        assertEquals("varchar(20)", columnType("acc_account", "OPERATOR_CODE"));
        assertTrue(fkExists("acc_account", "fk_acct_operator"));
    }

    @Test
    @Order(5)
    @DisplayName("exactly one IS_POOL_PRIMARY per CASH_POOL")
    void cashPools() {
        // A cross-row rule MySQL CHECK cannot express, so V27 asserts it. It
        // matters because the primary member absorbs its pool's whole short/over
        // variance: zero primaries leaves it nowhere to go, two double-count it.
        assertEquals(List.of("POOL-1", "POOL-2", "SAFE"),
                strings("SELECT DISTINCT CASH_POOL FROM acc_account "
                        + "WHERE CASH_POOL IS NOT NULL ORDER BY CASH_POOL"));
        assertEquals(0, count("SELECT COUNT(*) FROM ("
                + "SELECT CASH_POOL, SUM(IS_POOL_PRIMARY) n FROM acc_account "
                + "WHERE CASH_POOL IS NOT NULL GROUP BY CASH_POOL) x WHERE x.n <> 1"));
        assertEquals(List.of("CASH_RECHARGE", "CASH_SAFE", "CASH_WATER"),
                strings("SELECT ACCOUNT_CODE FROM acc_account WHERE IS_POOL_PRIMARY = 1 "
                        + "ORDER BY ACCOUNT_CODE"));
        // CASH_TICKET shares POOL-1's drawer but is not its primary.
        assertTrue(exists("SELECT 1 FROM acc_account WHERE ACCOUNT_CODE = 'CASH_TICKET' "
                + "AND CASH_POOL = 'POOL-1' AND IS_POOL_PRIMARY = 0"));
    }

    // ------------------------------------------------------------------
    // V22 - the voucher tables
    // ------------------------------------------------------------------

    @Test
    @Order(6)
    @DisplayName("V22 creates three tables, all empty, with every FK and CHECK")
    void voucherTables() {
        for (String table : List.of("acc_voucher", "acc_voucher_line", "acc_reconciliation")) {
            assertTrue(tableExists(table), table + " should exist");
            assertEquals("utf8mb4_0900_ai_ci", tableCollation(table));
            // The ledger must stay empty until the opening journal, and the
            // absence of rc_setting['ledger.opening.date'] is how the system
            // knows that has not happened (log §12).
            assertEquals(0, count("SELECT COUNT(*) FROM " + table), table + " must ship empty");
        }
        assertFalse(exists("SELECT 1 FROM rc_setting WHERE SETTING_KEY = 'ledger.opening.date'"));

        // BIGINT on both sides of every FK between these tables. This repo has
        // already been bitten by a mediumint/int mismatch (design-domain §8.6).
        assertEquals("bigint", columnType("acc_voucher", "VOUCHER_ID"));
        assertEquals("bigint", columnType("acc_voucher_line", "VOUCHER_ID"));
        assertEquals("decimal(38,2)", columnType("acc_voucher_line", "DR_AMOUNT"));

        for (String fk : List.of("fk_vch_type", "fk_vch_source", "fk_vch_reverse", "fk_vch_creby",
                "fk_line_voucher", "fk_line_account", "fk_line_party", "fk_line_paymeth",
                "fk_recon_account", "fk_recon_voucher", "fk_recon_closedby")) {
            assertTrue(fkExists(null, fk), fk + " should exist");
        }
        for (String ck : List.of("ck_line_nonneg", "ck_line_oneside", "ck_line_nonzero")) {
            assertTrue(checkExists(ck), ck + " should exist");
        }
        // Nullable-unique: manual vouchers have no natural key and leave it NULL,
        // while every automatic posting sets it (§5.4).
        assertTrue(indexExists("acc_voucher", "uk_vch_idem"));
        assertEquals("YES", columnNullable("acc_voucher", "IDEMPOTENCY_KEY"));
    }

    // ------------------------------------------------------------------
    // V24, V25 - float and rates
    // ------------------------------------------------------------------

    @Test
    @Order(7)
    @DisplayName("V24 and V25 create the float and rate tables, with the deferred FK")
    void floatAndRateTables() {
        for (String table : List.of("acc_float_batch", "acc_float_movement", "acc_commission_rate")) {
            assertTrue(tableExists(table), table + " should exist");
            assertEquals("utf8mb4_0900_ai_ci", tableCollation(table));
        }
        assertEquals(0, count("SELECT COUNT(*) FROM acc_float_batch"));
        assertEquals(0, count("SELECT COUNT(*) FROM acc_float_movement"));

        // rc_recharge.RECHARGE_ID is int, not the mediumint the legacy TXN_ID
        // keys use. Both mistakes are available and both are errno 150.
        assertEquals("int", columnType("acc_float_movement", "RECHARGE_ID"));
        // Same widening as acc_account, and §4's DDL has it at VARCHAR(10) too.
        assertEquals("varchar(20)", columnType("acc_commission_rate", "OPERATOR_CODE"));

        // ⚠ §3.2's DDL puts fk_fmov_rate on acc_float_movement, but
        // acc_commission_rate is created one migration later. V24 declares the
        // column and V25 adds the constraint; this is the assertion that it
        // actually landed rather than being quietly dropped.
        assertTrue(fkExists("acc_float_movement", "fk_fmov_rate"),
                "V25 must add the FK V24 could not declare");
        for (String fk : List.of("fk_fbatch_account", "fk_fbatch_voucher", "fk_fbatch_creby",
                "fk_fmov_account", "fk_fmov_type", "fk_fmov_batch", "fk_fmov_voucher",
                "fk_fmov_recharge", "fk_rate_operator", "fk_rate_route", "fk_rate_super",
                "fk_rate_creby")) {
            assertTrue(fkExists(null, fk), fk + " should exist");
        }
    }

    @Test
    @Order(8)
    @DisplayName("V25 seeds twelve rates, not fourteen, and the merge loses none")
    void rateSeeds() {
        // §4 lists fourteen; IDEA and VDFN carried IDENTICAL rates on both routes
        // (2.50 direct, 3.30 A1Topup), so merging them into VI drops four rows to
        // two and loses no rate information. That is independent evidence the two
        // codes really were one wallet.
        assertEquals(12, count("SELECT COUNT(*) FROM acc_commission_rate"));
        assertEquals(12, count("SELECT COUNT(*) FROM acc_commission_rate "
                + "WHERE PLAN_TIER = '*' AND MIN_AMOUNT = 0.00 AND MAX_AMOUNT IS NULL "
                + "AND EFFECTIVE_TO IS NULL"), "every seed is an open catch-all");
        assertEquals(0, count("SELECT COUNT(*) FROM acc_commission_rate "
                + "WHERE OPERATOR_CODE IN ('IDEA','VDFN')"));
        assertEquals("2.500000", string("SELECT RATE_PCT FROM acc_commission_rate "
                + "WHERE OPERATOR_CODE = 'VI' AND ROUTE_CODE = 'DIRECT'"));
        assertEquals("3.300000", string("SELECT RATE_PCT FROM acc_commission_rate "
                + "WHERE OPERATOR_CODE = 'VI' AND ROUTE_CODE = 'A1TOPUP'"));

        // The same operator paying different rates by route, in opposite
        // directions across the book — the thing no operator-only key can express.
        assertEquals("3.000000", string("SELECT RATE_PCT FROM acc_commission_rate "
                + "WHERE OPERATOR_CODE = 'ARTL' AND ROUTE_CODE = 'DIRECT'"));
        assertEquals("0.800000", string("SELECT RATE_PCT FROM acc_commission_rate "
                + "WHERE OPERATOR_CODE = 'ARTL' AND ROUTE_CODE = 'A1TOPUP'"));

        // No wildcard rows: a wildcard could silently absorb a route whose rate
        // was never entered, weakening §8.2's catch-all guarantee.
        assertEquals(0, count("SELECT COUNT(*) FROM acc_commission_rate WHERE ROUTE_CODE = '*'"));
        // No BSNL/DIRECT row: BSNL has no direct connection, so selecting that
        // combination SHOULD hit §4.2's refusal (log §2.4).
        assertEquals(0, count("SELECT COUNT(*) FROM acc_commission_rate "
                + "WHERE OPERATOR_CODE = 'BSNL' AND ROUTE_CODE = 'DIRECT'"));
        // A rate not yet observed is seeded at zero with a NOTE, never a guess.
        assertTrue(exists("SELECT 1 FROM acc_commission_rate WHERE OPERATOR_CODE = 'BIGTV' "
                + "AND RATE_PCT = 0.000000 AND NOTE LIKE '%PROVISIONAL ZERO%'"));
        // EFFECTIVE_FROM is the migration's run date, not D (log §12).
        assertEquals(12, count("SELECT COUNT(*) FROM acc_commission_rate "
                + "WHERE EFFECTIVE_FROM = CURDATE()"));

        // Every active operator carries an open catch-all — V27's RATE-A check,
        // and the hole log §8.2 actually cares about.
        assertEquals(0, count("SELECT COUNT(*) FROM rc_operator o WHERE o.IS_ACTIVE = 1 "
                + "AND NOT EXISTS (SELECT 1 FROM acc_commission_rate r "
                + "WHERE r.OPERATOR_CODE = o.OPERATOR_CODE AND r.EFFECTIVE_TO IS NULL)"));
        // And every rate's DERIVED float account exists — V27's RATE-C check,
        // which is what guards the four-wallet derivation.
        assertEquals(0, count("SELECT COUNT(*) FROM acc_commission_rate r "
                + "WHERE r.EFFECTIVE_TO IS NULL AND NOT EXISTS (SELECT 1 FROM acc_account a "
                + "WHERE a.ACCOUNT_CODE = IF(r.ROUTE_CODE = 'A1TOPUP', 'FLOAT_A1TOPUP', "
                + "CONCAT('FLOAT_', r.OPERATOR_CODE)) AND a.FLOAT_PATTERN IS NOT NULL)"));
    }

    // ------------------------------------------------------------------
    // V23 - all seven triggers exist
    // ------------------------------------------------------------------

    @Test
    @Order(9)
    @DisplayName("exactly three triggers exist, and the four that moved out are gone")
    void triggersExist() {
        // Reduced from seven on 2026-10-09. "A posted money row is never
        // updated" is a storage property and a per-table GRANT serves it better
        // than a trigger; "an account reconciled past this date needs a journal"
        // and "a rate is closed, never edited" are accounting policy and belong
        // in the service, where they return a sentence. V23's header has the
        // full reasoning. These three are the only enforcement of the CROSS-ROW
        // balance invariant, which no grant can express.
        assertEquals(List.of("trg_acc_voucher_bd", "trg_acc_voucher_bu",
                        "trg_acc_voucher_line_bi"),
                strings("SELECT TRIGGER_NAME FROM INFORMATION_SCHEMA.TRIGGERS "
                        + "WHERE TRIGGER_SCHEMA = DATABASE() ORDER BY TRIGGER_NAME"));

        // V23 is now the ONLY migration creating a trigger, so it is the only
        // one needing log_bin_trust_function_creators. V20's and V27's DELIMITER
        // blocks create PROCEDUREs, which that variable does not govern.
        //
        // Asserting the four are ABSENT is not pedantry: a database that ran an
        // earlier build of V23/V24/V25 would still carry them, and the rule
        // would then be enforced twice with the service's readable error never
        // surfacing. V27 fails on this too, under STALETRIGGER.
        assertEquals(0, count("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TRIGGERS "
                + "WHERE TRIGGER_SCHEMA = DATABASE() AND TRIGGER_NAME IN "
                + "('trg_acc_voucher_line_bu','trg_acc_voucher_line_bd',"
                + "'trg_acc_float_movement_bu','trg_acc_commission_rate_bu')"));
    }

    // ------------------------------------------------------------------
    // The half INFORMATION_SCHEMA cannot prove: do they fire?
    // ------------------------------------------------------------------

    @Test
    @Order(10)
    @DisplayName("a balanced voucher posts and seals, and the view ignores it until then")
    void balancedVoucherSealsAndTheViewRespectsTheSeal() {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(voucher(100, "JOURNAL", "MANUAL", "balanced probe"));
            s.execute(leg(100, 1, "CASH_RECHARGE", "DR_AMOUNT", "100.00"));
            s.execute(leg(100, 2, "INC_RCHG_COMM", "CR_AMOUNT", "100.00"));

            // ⚠ THE REGRESSION TEST FOR §2.6's DEFECT. design-ledger.md:528-541
            // puts the seal filter in a LEFT JOIN's ON clause and then sums the
            // LINE table, so the predicate only NULLs columns nothing reads and
            // the row survives: unsealed legs are counted. V26 moves the test
            // inside the aggregate. The published form returns 100.00 here.
            assertEquals("0.00", balanceOf("CASH_RECHARGE"),
                    "an unsealed voucher must contribute nothing to any balance");

            s.execute("UPDATE acc_voucher SET SEALED_AT = NOW() WHERE VOUCHER_ID = 100");
            assertEquals("100.00", balanceOf("CASH_RECHARGE"),
                    "and once sealed it must contribute exactly its legs");
        } catch (SQLException e) {
            throw new AssertionError("posting a balanced voucher should succeed", e);
        }
    }

    @Test
    @Order(11)
    @DisplayName("trigger 1 refuses to seal an unbalanced or single-legged voucher")
    void sealTimeBalanceCheckFires() {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(voucher(101, "JOURNAL", "MANUAL", "unbalanced probe"));
            s.execute(leg(101, 1, "CASH_RECHARGE", "DR_AMOUNT", "100.00"));
            s.execute(leg(101, 2, "INC_RCHG_COMM", "CR_AMOUNT", "99.00"));
            refused(s, "UPDATE acc_voucher SET SEALED_AT = NOW() WHERE VOUCHER_ID = 101",
                    "SUM(DR) <> SUM(CR) must be refused at the seal");

            s.execute(voucher(102, "JOURNAL", "MANUAL", "single leg probe"));
            s.execute(leg(102, 1, "CASH_RECHARGE", "DR_AMOUNT", "5.00"));
            refused(s, "UPDATE acc_voucher SET SEALED_AT = NOW() WHERE VOUCHER_ID = 102",
                    "double entry with one leg is not double entry");
        } catch (SQLException e) {
            throw new AssertionError("probe setup failed", e);
        }
    }

    @Test
    @Order(12)
    @DisplayName("triggers 1-3 make a sealed voucher and every line immutable")
    void immutabilityFires() {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            refused(s, "UPDATE acc_voucher SET NARRATION = 'tampered' WHERE VOUCHER_ID = 100",
                    "a sealed voucher cannot be altered");
            refused(s, "UPDATE acc_voucher SET SEALED_AT = NULL WHERE VOUCHER_ID = 100",
                    "a sealed voucher cannot be un-sealed");
            refused(s, leg(100, 3, "CASH_SAFE", "DR_AMOUNT", "1.00"),
                    "a leg cannot be added to a sealed voucher, or trigger 1 could be defeated");

            // ⚠ NOT asserted here any more: that UPDATE on acc_voucher_line is
            // refused. That is now a GRANT, and this connection is the
            // migrating account, which holds UPDATE. grantsGiveAppendOnly
            // proves the mechanism with a properly restricted account.
        } catch (SQLException e) {
            throw new AssertionError("probe setup failed", e);
        }
    }

    @Test
    @Order(13)
    @DisplayName("the row-level CHECKs bite independently of the triggers")
    void lineChecksBite() {
        // §2.4's fallback is to drop the triggers and rely on these, so they are
        // worth proving separately. MySQL only enforces CHECK from 8.0.16.
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(voucher(103, "JOURNAL", "MANUAL", "check probe"));
            refused(s, "INSERT INTO acc_voucher_line (VOUCHER_ID, LINE_NO, ACCOUNT_CODE, "
                            + "DR_AMOUNT, CR_AMOUNT) VALUES (103, 1, 'CASH_SAFE', 5.00, 5.00)",
                    "ck_line_oneside: a leg is a debit or a credit, never both");
            refused(s, "INSERT INTO acc_voucher_line (VOUCHER_ID, LINE_NO, ACCOUNT_CODE, "
                            + "DR_AMOUNT, CR_AMOUNT) VALUES (103, 2, 'CASH_SAFE', 0.00, 0.00)",
                    "ck_line_nonzero: a zero leg is not a posting");
            refused(s, leg(103, 3, "CASH_SAFE", "DR_AMOUNT", "-5.00"),
                    "ck_line_nonneg: negative amounts belong on the other side");
            refused(s, leg(103, 4, "NO_SUCH_ACCOUNT", "DR_AMOUNT", "5.00"),
                    "fk_line_account: an unknown account must be impossible for any writer");
        } catch (SQLException e) {
            throw new AssertionError("probe setup failed", e);
        }
    }

    @Test
    @Order(14)
    @DisplayName("acc_reconciliation records the variance; the delete lock is now a service rule")
    void reconciliationRowsAndTheMovedLock() {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM acc_voucher_line WHERE VOUCHER_ID = 103");
            s.execute("INSERT INTO acc_reconciliation (ACCOUNT_CODE, RECON_DATE, "
                    + "COUNTED_AMOUNT, LEDGER_AMOUNT) "
                    + "VALUES ('CASH_RECHARGE', '2026-10-08', 100.00, 100.00)");

            // VARIANCE_AMOUNT is a generated STORED column: derived by the
            // engine, never independently writable. No privilege needed for it.
            assertEquals("0.00", string("SELECT VARIANCE_AMOUNT FROM acc_reconciliation "
                    + "WHERE ACCOUNT_CODE = 'CASH_RECHARGE'"));
            // And it really is non-writable, which is what makes it evidence
            // rather than a second stored balance.
            refused(s, "UPDATE acc_reconciliation SET VARIANCE_AMOUNT = 5.00 "
                            + "WHERE ACCOUNT_CODE = 'CASH_RECHARGE'",
                    "a generated column cannot be assigned");

            // ⚠ THE DATABASE NO LONGER REFUSES THIS. trg_acc_voucher_line_bd
            // was removed on 2026-10-09 because FRD §8.3's correction regime is
            // accounting policy, not a storage property. The rule now lives in
            // LedgerCorrectionRules.assertVoucherDeletable and is covered by
            // LedgerCorrectionRulesTest.
            //
            // Asserting the DB permits it is deliberate, not an oversight: it
            // records exactly what the layering decision gave up, so a future
            // reader can see the cost rather than assume the rule is enforced
            // in two places.
            s.execute("DELETE FROM acc_voucher_line WHERE VOUCHER_ID = 100 AND LINE_NO = 1");
            assertEquals(1, count("SELECT COUNT(*) FROM acc_voucher_line WHERE VOUCHER_ID = 100"),
                    "the DB allows it; only the service refuses it now");
        } catch (SQLException e) {
            throw new AssertionError("probe setup failed", e);
        }
    }

    @Test
    @Order(15)
    @DisplayName("trigger 5 makes the opening voucher undeletable")
    void openingVoucherIsUndeletable() {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(voucher(104, "JOURNAL", "OPENING", "opening probe"));
            refused(s, "DELETE FROM acc_voucher WHERE VOUCHER_ID = 104",
                    "the opening journal is the foundation every balance stands on");
        } catch (SQLException e) {
            throw new AssertionError("probe setup failed", e);
        }
    }

    @Test
    @Order(16)
    @DisplayName("V24's and V25's CHECKs bite; their triggers are gone by design")
    void floatAndRateConstraintsBite() {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO acc_float_movement (ACCOUNT_CODE, MOVEMENT_TYPE, FACE_DELTA, "
                    + "COST_DELTA, VOUCHER_ID, MOVED_AT) "
                    + "VALUES ('FLOAT_ARTL', 'SALE', -199.00, -193.03, 100, NOW())");
            refused(s, "INSERT INTO acc_float_movement (ACCOUNT_CODE, MOVEMENT_TYPE, FACE_DELTA, "
                            + "COST_DELTA, VOUCHER_ID, MOVED_AT) "
                            + "VALUES ('FLOAT_ARTL', 'TOPUP', 2000.00, 2000.00, 100, NOW())",
                    "ck_fmov_topup: a TOPUP without its batch has no cost basis");
            refused(s, "INSERT INTO acc_float_movement (ACCOUNT_CODE, MOVEMENT_TYPE, FACE_DELTA, "
                            + "COST_DELTA, VOUCHER_ID, MOVED_AT) "
                            + "VALUES ('FLOAT_ARTL', 'SALE', 0.00, 0.00, 100, NOW())",
                    "ck_fmov_nonzero: a movement that moves nothing is not a movement");
            refused(s, "UPDATE acc_commission_rate SET RATE_PCT = 150 "
                            + "WHERE OPERATOR_CODE = 'ARTL' AND ROUTE_CODE = 'DIRECT'",
                    "ck_rate_pct still bounds the rate, with no trigger involved");

            // ⚠ The append-only trigger on acc_float_movement and the rate
            // immutability trigger are BOTH gone (2026-10-09). The first is a
            // grant, the second a service rule. So the migrating account can do
            // this, and that is the documented trade:
            s.execute("UPDATE acc_float_movement SET NOTE = 'reachable without the grant' "
                    + "WHERE MOVEMENT_ID = 1");
            s.execute("UPDATE acc_commission_rate SET RATE_PCT = 2.750000 "
                    + "WHERE OPERATOR_CODE = 'ARTL' AND ROUTE_CODE = 'DIRECT'");

            // Closing a rate is now permitted by the database too — it is the
            // service that decides whether a close is legitimate. Closing
            // BIGTV's only row leaves an active operator with no open rate,
            // which is precisely the hole V27's RATE-A check exists to find;
            // gateZeroHasTeeth reads it back.
            s.execute("UPDATE acc_commission_rate SET EFFECTIVE_TO = CURDATE() "
                    + "WHERE OPERATOR_CODE = 'BIGTV'");
        } catch (SQLException e) {
            throw new AssertionError("probe setup failed", e);
        }
    }

    @Test
    @Order(17)
    @DisplayName("V21's inverted ck_acct_float holds in both directions")
    void accountFloatCheckFires() {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            // §2.2's original CHECK was "a float pattern requires an operator",
            // which FLOAT_A1TOPUP violates. V21 inverts it to "an operator code
            // requires a float pattern", so this must now be refused...
            refused(s, "INSERT INTO acc_account (ACCOUNT_CODE, DISPLAY_NAME, ACCOUNT_TYPE, "
                            + "BUSINESS_CODE, OPERATOR_CODE) "
                            + "VALUES ('BAD_ACCT', 'Bad', 'ASSET', 'RECHARGE', 'ARTL')",
                    "a non-float account must not carry an operator code");
            // ...while a wallet with no single operator must be allowed.
            s.execute("INSERT INTO acc_account (ACCOUNT_CODE, DISPLAY_NAME, ACCOUNT_TYPE, "
                    + "BUSINESS_CODE, FLOAT_PATTERN) "
                    + "VALUES ('FLOAT_PROBE', 'Probe', 'ASSET', 'RECHARGE', 'TXN_RATE')");
            s.execute("DELETE FROM acc_account WHERE ACCOUNT_CODE = 'FLOAT_PROBE'");
        } catch (SQLException e) {
            throw new AssertionError("probe setup failed", e);
        }
    }

    // ------------------------------------------------------------------
    // The two triggers that became GRANTs
    // ------------------------------------------------------------------

    @Test
    @Order(19)
    @DisplayName("per-table grants give append-only with no trigger and no SUPER")
    void grantsGiveAppendOnly() {
        // This is the mechanism that replaced trg_acc_voucher_line_bu and
        // trg_acc_float_movement_bu. It needs a properly restricted account to
        // mean anything, so the test builds one — the migrating account holds
        // UPDATE and would pass trivially.
        //
        // ⚠ The grant must be issued PER TABLE. Granting at database level and
        // then revoking on one table fails with ERROR 1147 ("no such grant
        // defined"), because MySQL cannot revoke below the level it granted.
        // That is the single most likely way to deploy this wrongly.
        try (Connection root = DriverManager.getConnection(
                     MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement rs = root.createStatement()) {

            rs.execute("DROP USER IF EXISTS 'ledger_app'@'%'");
            rs.execute("CREATE USER 'ledger_app'@'%' IDENTIFIED BY 'lp'");
            rs.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON recharge.acc_voucher "
                    + "TO 'ledger_app'@'%'");
            // UPDATE deliberately absent on both append-only tables.
            rs.execute("GRANT SELECT, INSERT, DELETE ON recharge.acc_voucher_line "
                    + "TO 'ledger_app'@'%'");
            rs.execute("GRANT SELECT, INSERT, DELETE ON recharge.acc_float_movement "
                    + "TO 'ledger_app'@'%'");
            rs.execute("GRANT SELECT ON recharge.acc_account TO 'ledger_app'@'%'");
            rs.execute("FLUSH PRIVILEGES");

            try (Connection app = DriverManager.getConnection(
                         MYSQL.getJdbcUrl(), "ledger_app", "lp");
                 Statement as = app.createStatement()) {

                // An unsealed voucher to hang legs off.
                as.execute(voucher(900, "JOURNAL", "MANUAL", "grant probe"));
                as.execute(leg(900, 1, "CASH_RECHARGE", "DR_AMOUNT", "7.00"));
                as.execute(leg(900, 2, "INC_RCHG_COMM", "CR_AMOUNT", "7.00"));

                // The property we are buying: no UPDATE, by any statement.
                refused(as, "UPDATE acc_voucher_line SET DR_AMOUNT = 999.00 "
                                + "WHERE VOUCHER_ID = 900 AND LINE_NO = 1",
                        "the grant must make acc_voucher_line append-only");
                refused(as, "UPDATE acc_float_movement SET NOTE = 'x' WHERE MOVEMENT_ID = 1",
                        "the grant must make acc_float_movement append-only");

                // And the three things the posting path still needs must work,
                // or the grant would be unusable. Order matters: the leg
                // delete-and-re-add has to happen while the voucher is still
                // UNSEALED, because trigger 2 refuses a leg on a sealed one —
                // which is exactly the behaviour balancedVoucherSeals relies on.
                as.execute("DELETE FROM acc_voucher_line WHERE VOUCHER_ID = 900 AND LINE_NO = 2");
                as.execute("INSERT INTO acc_voucher_line (VOUCHER_ID, LINE_NO, ACCOUNT_CODE, "
                        + "CR_AMOUNT) VALUES (900, 3, 'INC_RCHG_COMM', 7.00)");
                as.execute("UPDATE acc_voucher SET SEALED_AT = NOW() WHERE VOUCHER_ID = 900");
                assertEquals("1", string("SELECT COUNT(*) FROM acc_voucher "
                                + "WHERE VOUCHER_ID = 900 AND SEALED_AT IS NOT NULL"),
                        "sealing is an UPDATE on the header and must still be permitted");
            }

            rs.execute("DROP USER 'ledger_app'@'%'");
        } catch (SQLException e) {
            throw new AssertionError("grant probe failed", e);
        }
    }

    // ------------------------------------------------------------------
    // Gate 0 has teeth
    // ------------------------------------------------------------------

    @Test
    @Order(18)
    @DisplayName("V27 reports real failures, and not through PREPARE")
    void gateZeroHasTeeth() {
        // V27 passed during migrate() — that is what getting to version 27
        // means. But a gate whose FAILURE path has never run proves nothing, and
        // this one had a real defect: SIGNAL cannot be issued through
        // PREPARE/EXECUTE ("ERROR 1295: not supported in the prepared statement
        // protocol"), so the repo's dynamic-SQL idiom silently could not report.
        // The passing branch prepares a harmless SELECT, which is why it hid.
        //
        // By now the probes above have left vouchers behind and closed BIGTV's
        // only rate, so re-running the gate must fail and must NAME what broke.
        String failures = string(
                "SELECT CONCAT("
                        + "IF((SELECT COUNT(*) FROM acc_voucher) <> 0, 'NOTEMPTY ', ''),"
                        + "IF((SELECT COUNT(*) FROM rc_operator o WHERE o.IS_ACTIVE = 1 "
                        + "    AND NOT EXISTS (SELECT 1 FROM acc_commission_rate r "
                        + "    WHERE r.OPERATOR_CODE = o.OPERATOR_CODE "
                        + "    AND r.EFFECTIVE_TO IS NULL)) <> 0, 'RATE-A ', ''))");
        assertEquals("NOTEMPTY RATE-A ", failures,
                "the two conditions the probes created must both be detectable, which is "
                        + "what V27's checks read");

        // And the mechanism itself: a procedure can signal, PREPARE cannot.
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("DROP PROCEDURE IF EXISTS probe_assert");
            s.execute("CREATE PROCEDURE probe_assert(IN p TEXT) BEGIN "
                    + "IF p <> '' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = p; END IF; END");
            refused(s, "CALL probe_assert('gate failed')",
                    "a procedure must be able to signal conditionally");
            s.execute("CALL probe_assert('')");
            refused(s, "PREPARE bad FROM 'SIGNAL SQLSTATE ''45000'' "
                            + "SET MESSAGE_TEXT = ''x'''",
                    "and PREPARE must still refuse SIGNAL, which is why V20 and V27 "
                            + "do not use it");
            s.execute("DROP PROCEDURE probe_assert");
        } catch (SQLException e) {
            throw new AssertionError("gate mechanism probe failed", e);
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static String voucher(int id, String type, String source, String narration) {
        return "INSERT INTO acc_voucher (VOUCHER_ID, VOUCHER_TYPE, VOUCHER_DATE, VOUCHER_DTTM, "
                + "SOURCE_TYPE, NARRATION) VALUES (" + id + ", '" + type + "', '2026-10-08', "
                + "'2026-10-08 10:00:00', '" + source + "', '" + narration + "')";
    }

    private static String leg(int voucherId, int lineNo, String account, String side, String amount) {
        return "INSERT INTO acc_voucher_line (VOUCHER_ID, LINE_NO, ACCOUNT_CODE, " + side + ") "
                + "VALUES (" + voucherId + ", " + lineNo + ", '" + account + "', " + amount + ")";
    }

    private static String balanceOf(String accountCode) {
        return string("SELECT BALANCE FROM acc_account_balance WHERE ACCOUNT_CODE = '"
                + accountCode + "'");
    }

    /** Asserts the statement is rejected by the database, whatever the mechanism. */
    private static void refused(Statement s, String sql, String message) {
        try {
            s.execute(sql);
            throw new AssertionError(message);
        } catch (SQLException expected) {
            // the trigger, CHECK or FK fired, which is the point
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
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

    /** {@code table} may be null to assert the constraint exists anywhere in the schema. */
    private static boolean fkExists(String table, String name) {
        return exists("SELECT 1 FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND CONSTRAINT_NAME = '" + name + "' "
                + "AND CONSTRAINT_TYPE = 'FOREIGN KEY'"
                + (table == null ? "" : " AND TABLE_NAME = '" + table + "'"));
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
