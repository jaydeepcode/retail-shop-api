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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V34 applied to a production-shaped database.
 *
 * <p>⚠ WHAT THIS TEST IS FOR IS A RULE, NOT A ROW. The row is one `INSERT` and
 * V34 asserts its own presence before it finishes, so a test that only re-checks
 * the spelling of {@code INC_SUNDRY_SHOP} would be worth nothing. What is worth
 * testing is the condition {@code design-ledger-api.md} §1.1 states for a day
 * close whose variance is explained in the `IN` direction: the named account
 * must be <b>an {@code INCOME} account with {@code ALLOW_MANUAL = TRUE}</b>.
 * Before V34 the count of accounts satisfying that was <b>zero</b>, which is why
 * two of artboard 9's four variance buttons could not be built at all
 * (next-session-s5.md, "the income-account gap"). {@link
 * SatisfiesTheRule#exactlyOneIncomeAccountIsManuallyPostable()} is that count,
 * and it fails on every commit before this one.
 *
 * <p>⚠ IT ALSO ASSERTS WHAT V34 DELIBERATELY DID NOT DO. The cheapest way to
 * make the §1.1 rule satisfiable was to flip {@code INC_CASH_OVER}'s
 * {@code ALLOW_MANUAL} to TRUE — no migration, no cap move. That was considered
 * and rejected by the owner on 2026-11-02 because {@code INC_CASH_OVER} answers
 * exactly one question, "how much can we not account for?", and mixing roughly
 * ₹1,000 a year of rounding into it would hide a genuine unexplained ₹400 while
 * looking like compliance. A later reader hitting the same 422 will find the
 * same one-line shortcut, so {@link ConsidereredAndRejected} nails the flag
 * down with the reason attached.
 *
 * <p>Built on {@code TicketBookingMigrationTest}'s pattern — a throwaway MySQL
 * 8.0.43 loaded from {@code db/legacy-baseline.sql} and baselined at 8, which is
 * the shape V21-V25's foreign keys and triggers need. Target 34, matching
 * {@code spring.flyway.target}; the other migration tests pin 12, 27, 28, 30 and
 * 33, so each gate keeps asserting the state of its own slice and V34 could not
 * quietly change what any of them sees.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("V34 applied to a production-shaped database")
class SundryIncomeMigrationTest {

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
                // 34, matching spring.flyway.target. The cap moved 33 -> 34 with
                // the owner's decision of 2026-11-02 for this one migration;
                // V13-V19 are below it, still excluded, and still do not exist
                // as files, so the move cannot admit them.
                .target(org.flywaydb.core.api.MigrationVersion.fromVersion("34"))
                .outOfOrder(false)
                .load()
                .migrate();
    }

    @Test
    @DisplayName("V34 follows V33, and nothing from the cutover set appears")
    void appliedVersions() {
        assertEquals(List.of("9", "10", "11", "12", "20", "21", "22", "23", "24",
                        "25", "26", "27", "28", "29", "30", "31", "32", "33", "34"),
                strings("SELECT version FROM flyway_schema_history "
                        + "WHERE success = 1 AND type = 'SQL' ORDER BY installed_rank"),
                "V13-V19 must never appear here");
    }

    @Nested
    @DisplayName("The rule §1.1 states for an IN-direction variance")
    class SatisfiesTheRule {

        @Test
        @DisplayName("⚠ exactly one INCOME account is manually postable — it was none")
        void exactlyOneIncomeAccountIsManuallyPostable() {
            // THE TEST THAT FAILS WITHOUT V34, and the reason the migration
            // exists. V21 seeds five income accounts, all ALLOW_MANUAL = FALSE,
            // so this count was 0 and `direction: "IN"` had no account it could
            // legally name.
            assertEquals(List.of("INC_SUNDRY_SHOP"),
                    strings("SELECT ACCOUNT_CODE FROM acc_account "
                            + "WHERE ACCOUNT_TYPE = 'INCOME' AND ALLOW_MANUAL = TRUE "
                            + "AND IS_ACTIVE = TRUE ORDER BY ACCOUNT_CODE"));
        }

        @Test
        @DisplayName("the expense side still has its five, so OUT is unaffected")
        void outDirectionUnchanged() {
            // `direction: "OUT"` was always buildable. If V34 had touched the
            // expense accounts this count would move, and the close's existing
            // half would change behaviour — which the brief forbids.
            assertEquals(5, count("SELECT COUNT(*) FROM acc_account "
                    + "WHERE ACCOUNT_TYPE = 'EXPENSE' AND ALLOW_MANUAL = TRUE "
                    + "AND IS_ACTIVE = TRUE"));
        }

        @Test
        @DisplayName("the account is shaped as the mirror of EXP_SUNDRY_SHOP")
        void mirrorsTheExpenseAccount() {
            assertEquals(List.of("Sundry Income - Shop|INCOME|SHARED|INCOME|0|1|0|540"),
                    strings("SELECT CONCAT_WS('|', DISPLAY_NAME, ACCOUNT_TYPE, BUSINESS_CODE, "
                            + "REPORT_GROUP, IS_RECONCILABLE, ALLOW_MANUAL, REQUIRES_PARTY, "
                            + "SORT_ORDER) FROM acc_account "
                            + "WHERE ACCOUNT_CODE = 'INC_SUNDRY_SHOP'"));

            // SHARED on both sides: neither a rounding difference nor a
            // photocopy belongs to recharge, tickets or water.
            assertEquals(List.of("SHARED", "SHARED"),
                    strings("SELECT BUSINESS_CODE FROM acc_account "
                            + "WHERE ACCOUNT_CODE IN ('EXP_SUNDRY_SHOP', 'INC_SUNDRY_SHOP') "
                            + "ORDER BY ACCOUNT_CODE"));
        }

        @Test
        @DisplayName("it is not reconcilable, not a float, and in no cash pool")
        void notCountedAndNotAFloat() {
            // An income account is never counted, so it must not appear in the
            // close preview's list of reconcilable accounts, and FLOAT_PATTERN
            // NULL is what keeps it out of the float wallets ck_acct_float
            // governs.
            assertTrue(exists("SELECT 1 FROM acc_account WHERE ACCOUNT_CODE = 'INC_SUNDRY_SHOP' "
                    + "AND IS_RECONCILABLE = FALSE AND FLOAT_PATTERN IS NULL "
                    + "AND OPERATOR_CODE IS NULL AND CASH_POOL IS NULL "
                    + "AND IS_POOL_PRIMARY = FALSE"));
        }
    }

    @Nested
    @DisplayName("What V34 deliberately did not do")
    class ConsidereredAndRejected {

        @Test
        @DisplayName("⚠ INC_CASH_OVER is still ALLOW_MANUAL = FALSE, on purpose")
        void cashOverStaysMachineOwned() {
            // Flipping this flag satisfies §1.1 with no migration at all, and
            // destroys the only figure that answers "how much can we not
            // account for?" (owner, 2026-11-02). If a later change makes this
            // fail, read V34's header before changing the test.
            assertTrue(exists("SELECT 1 FROM acc_account WHERE ACCOUNT_CODE = 'INC_CASH_OVER' "
                    + "AND ALLOW_MANUAL = FALSE"));
            assertTrue(exists("SELECT 1 FROM acc_account WHERE ACCOUNT_CODE = 'EXP_CASH_SHORT' "
                    + "AND ALLOW_MANUAL = FALSE"));
        }

        @Test
        @DisplayName("V34 adds one row and changes none")
        void oneRowAndNothingElse() {
            // V27's gate asserts 32 accounts and passes, because it runs BEFORE
            // this file. 33 is that 32 plus this one; 29 is §2.2's 28 plus it.
            // The other four are the float wallets of log §2.4.
            assertEquals(33, count("SELECT COUNT(*) FROM acc_account"));
            assertEquals(29, count("SELECT COUNT(*) FROM acc_account "
                    + "WHERE FLOAT_PATTERN IS NULL"));
            assertEquals(4, count("SELECT COUNT(*) FROM acc_account "
                    + "WHERE FLOAT_PATTERN IS NOT NULL"));

            // The five income accounts V21 seeded are untouched — same flags
            // they had, so no existing posting path changes shape.
            assertEquals(List.of("INC_CASH_OVER", "INC_RCHG_COMM", "INC_RCHG_SURCHARGE",
                            "INC_TKT_COMM", "INC_WATER_TRIP"),
                    strings("SELECT ACCOUNT_CODE FROM acc_account "
                            + "WHERE ACCOUNT_TYPE = 'INCOME' AND ALLOW_MANUAL = FALSE "
                            + "ORDER BY ACCOUNT_CODE"));
        }

        @Test
        @DisplayName("no second sundry income account, and no write-off account")
        void noExtraAccounts() {
            // Reporting photocopy income apart from rounding is a second
            // account later, not part of this file. And §9.2: a UPI residue is
            // never written off automatically, so no write-off account exists.
            assertEquals(1, count("SELECT COUNT(*) FROM acc_account "
                    + "WHERE ACCOUNT_CODE LIKE 'INC_SUNDRY%'"));
            assertFalse(exists("SELECT 1 FROM acc_account "
                    + "WHERE ACCOUNT_CODE LIKE '%WRITE_OFF%' "
                    + "OR ACCOUNT_CODE LIKE '%WRITEOFF%'"));
        }
    }

    @Nested
    @DisplayName("The migration is re-runnable and the row is usable")
    class Behaviour {

        @Test
        @DisplayName("the WHERE NOT EXISTS guard makes a re-run a no-op")
        void reRunIsANoOp() {
            // Flyway will not re-run V34, but local-dev and the owner's gate
            // both replay this SQL against databases whose state nobody has
            // checked. V21's seeding pattern is the reason it is safe.
            //
            // ⚠ THE AS ALIASES ARE LOAD-BEARING, and this test found that by
            // failing: MySQL names a derived table's literal column after its
            // VALUE, so an unaliased copy of V34's statement dies on
            // "Duplicate column name 'INCOME'" — 'INCOME' is both ACCOUNT_TYPE
            // and REPORT_GROUP here. Copied with the aliases, exactly as V34
            // carries them.
            exec("INSERT INTO acc_account (ACCOUNT_CODE, DISPLAY_NAME, ACCOUNT_TYPE, "
                    + "BUSINESS_CODE, REPORT_GROUP, IS_RECONCILABLE, ALLOW_MANUAL, "
                    + "REQUIRES_PARTY, SORT_ORDER) "
                    + "SELECT src.* FROM ("
                    + "SELECT 'INC_SUNDRY_SHOP' AS ACCOUNT_CODE, "
                    + "'Sundry Income - Shop' AS DISPLAY_NAME, 'INCOME' AS ACCOUNT_TYPE, "
                    + "'SHARED' AS BUSINESS_CODE, 'INCOME' AS REPORT_GROUP, "
                    + "FALSE AS IS_RECONCILABLE, TRUE AS ALLOW_MANUAL, "
                    + "FALSE AS REQUIRES_PARTY, 540 AS SORT_ORDER) AS src "
                    + "WHERE NOT EXISTS (SELECT 1 FROM acc_account a "
                    + "WHERE a.ACCOUNT_CODE = 'INC_SUNDRY_SHOP')");

            assertEquals(1, count("SELECT COUNT(*) FROM acc_account "
                    + "WHERE ACCOUNT_CODE = 'INC_SUNDRY_SHOP'"));
        }

        @Test
        @DisplayName("⚠ a RECEIPT crediting it survives the seal, which is the real gate")
        void aReceiptIntoItPostsAndSeals() {
            // The point of the account is step 3 of §1.1's close: "a RECEIPT
            // into the named income account when the direction is IN".
            // Asserting the row exists does not show a voucher can reach it —
            // V22's CHECKs and V23's triggers decide that, and an account's
            // flags are exactly what such a rule can fall foul of.
            //
            // ⚠ AND THE SEAL IS THE PART WORTH WRITING. Inserting legs proves
            // little: trigger 2 only refuses legs on an ALREADY sealed voucher,
            // so two inserts into an unsealed one would pass whatever the
            // accounts were. The balance and two-leg rules live in trigger 1,
            // BEFORE UPDATE on the parent, and fire only when SEALED_AT is set.
            // So this seals it, which is what a real close does.
            exec("INSERT INTO acc_voucher (VOUCHER_TYPE, VOUCHER_DATE, VOUCHER_DTTM, "
                    + "SOURCE_TYPE, NARRATION) VALUES ('RECEIPT', '2026-11-12', "
                    + "'2026-11-12 20:40:00', 'CASH_RECON', 'small change rounding, V34 test')");
            // NOT LAST_INSERT_ID(): every helper here opens its own connection
            // and that function is per-session, so it would read 0.
            long voucherId = count("SELECT VOUCHER_ID FROM acc_voucher "
                    + "WHERE NARRATION = 'small change rounding, V34 test'");
            assertTrue(voucherId > 0, "the voucher was not written");

            // Dr CASH_RECHARGE / Cr INC_SUNDRY_SHOP — ₹4 the drawer held and
            // rounding explains, the shape artboard 9's second button posts.
            exec("INSERT INTO acc_voucher_line (VOUCHER_ID, LINE_NO, ACCOUNT_CODE, "
                    + "DR_AMOUNT, CR_AMOUNT) VALUES (" + voucherId
                    + ", 1, 'CASH_RECHARGE', 4.00, 0.00)");
            exec("INSERT INTO acc_voucher_line (VOUCHER_ID, LINE_NO, ACCOUNT_CODE, "
                    + "DR_AMOUNT, CR_AMOUNT) VALUES (" + voucherId
                    + ", 2, 'INC_SUNDRY_SHOP', 0.00, 4.00)");

            exec("UPDATE acc_voucher SET SEALED_AT = '2026-11-12 20:40:05' "
                    + "WHERE VOUCHER_ID = " + voucherId);

            // Asserted as "is sealed" rather than against a formatted string,
            // which would be testing the JDBC driver's DATETIME rendering.
            assertTrue(exists("SELECT 1 FROM acc_voucher WHERE VOUCHER_ID = " + voucherId
                            + " AND SEALED_AT IS NOT NULL"),
                    "the seal is what proves trigger 1 accepted the posting");
            assertEquals(4L, count("SELECT CAST(SUM(CR_AMOUNT) AS SIGNED) "
                    + "FROM acc_voucher_line WHERE ACCOUNT_CODE = 'INC_SUNDRY_SHOP'"));
        }
    }

    // ------------------------------------------------------------------
    // Helpers, as in the other migration tests
    // ------------------------------------------------------------------

    private static void exec(String sql) {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        } catch (SQLException e) {
            throw new AssertionError("statement failed: " + sql, e);
        }
    }

    private static boolean exists(String sql) {
        return !strings(sql).isEmpty();
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
