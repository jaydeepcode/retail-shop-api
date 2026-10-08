-- ============================================================================
-- V27: Gate 0 — verify the ledger schema
-- ============================================================================
-- Spec: design-ledger.md §8.1's V26 row, plus the two cross-row assertions log
-- §8.2 and §9.1 add.
--
-- SCHEMA ASSERTIONS, NOT DATA ASSERTIONS. There is no ledger data yet and there
-- must not be: the opening journal is posted by an API call on go-live day
-- (log §12), and this gate asserts the voucher tables are still empty.
--
-- WHY THIS GATE EXISTS, SPECIFICALLY. spring.jpa.hibernate.ddl-auto can
-- substitute Hibernate's own DDL — which carries no foreign key, no CHECK, no
-- trigger and no index — and this repository's migrations are written with
-- CREATE TABLE IF NOT EXISTS guards, so they would then SKIP the real thing. The
-- ledger would appear to work while every structural guarantee in §2.4 was
-- absent. NO DATA GATE CAN SEE THAT CLASS OF DAMAGE; only an INFORMATION_SCHEMA
-- check can. S0 set ddl-auto to none/validate, and this is the assertion that it
-- stayed that way.
--
-- IT REPORTS EVERY FAILURE AT ONCE rather than stopping at the first. A gate that
-- names one missing foreign key per run turns a five-minute fix into five
-- migrate-fail cycles.
--
-- ⚠ WHAT THE RATE-COVERAGE ASSERTION CAN AND CANNOT BE AT THIS POINT IN THE
-- CHAIN. §8.1's V26 row asks that "every reachable (active operator, active
-- route) pair resolves to an open rate row". Read as a cross join that is false
-- by design and the gate would refuse to run:
--
--   * V10 leaves EVERY operator on DEFAULT_ROUTE_CODE = 'DIRECT'. V16 is what
--     repoints the ones the shop routes through A1Topup, and V16 is cutover work
--     (V13-V19) that this slice must not run.
--   * So BSNL and all five TV operators sit on DIRECT with no rate row — and
--     §4.2 says the BSNL/DIRECT row SHOULD be absent, because log §2.4
--     establishes BSNL has no direct connection and selecting that combination
--     should hit §4.2's refusal.
--   * SARAVATE is still an active route until V16 retires it, and carries no
--     rates at all by design.
--
-- "Reachable" therefore has no sound definition before V16. Asserting it against
-- the rate table's own contents would be circular. What this gate asserts
-- instead are three non-circular properties that together catch the hole log
-- §8.2 actually cares about — an operator that can trade with no rate on file:
--
--   (a) every ACTIVE operator carries at least one open catch-all rate row;
--   (b) every open rate row names an active operator and an active route;
--   (c) every open rate row's DERIVED float account exists and is active.
--
-- (c) is new, and it is the one that guards the four-wallet derivation V21
-- introduced: it closes the loop between the rate table and the chart of
-- accounts, so adding a rate for a new operator on DIRECT fails here unless its
-- float account was created too. The literal "every reachable pair" form belongs
-- in the gate that follows V16, where DEFAULT_ROUTE_CODE finally means something.
-- ============================================================================

SELECT 'V27: verifying the ledger schema...' AS Status;

SET @failures = '';

-- ============================================================================
-- 1. Every table and the view exist
-- ============================================================================
SET @missing = (
    SELECT GROUP_CONCAT(x.name ORDER BY x.name)
    FROM (
        SELECT 'ref_account_type' AS name
        UNION ALL SELECT 'ref_business'
        UNION ALL SELECT 'ref_voucher_type'
        UNION ALL SELECT 'ref_voucher_source'
        UNION ALL SELECT 'ref_float_pattern'
        UNION ALL SELECT 'ref_float_movement_type'
        UNION ALL SELECT 'acc_account'
        UNION ALL SELECT 'acc_voucher'
        UNION ALL SELECT 'acc_voucher_line'
        UNION ALL SELECT 'acc_reconciliation'
        UNION ALL SELECT 'acc_float_batch'
        UNION ALL SELECT 'acc_float_movement'
        UNION ALL SELECT 'acc_commission_rate'
    ) x
    WHERE NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.TABLES
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = x.name AND TABLE_TYPE = 'BASE TABLE'
    )
);
SET @failures = IF(@missing IS NULL, @failures, CONCAT(@failures, ' |TABLES:', @missing));

SET @missing = (
    SELECT 'acc_account_balance' FROM (SELECT 1) d
     WHERE NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.VIEWS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'acc_account_balance')
);
SET @failures = IF(@missing IS NULL, @failures, CONCAT(@failures, ' |VIEW:', @missing));

-- ============================================================================
-- 2. Collation on every table
-- ============================================================================
-- §8.2: a bare CHARSET=utf8mb4 can resolve to utf8mb4_general_ci and will then
-- not foreign-key against rs_cust_dtls, rc_operator or ref_payment_line_type.
-- Asserting the collation is how a table declared the sloppy way gets caught.
SET @missing = (
    SELECT GROUP_CONCAT(CONCAT(TABLE_NAME, '=', TABLE_COLLATION) ORDER BY TABLE_NAME)
    FROM INFORMATION_SCHEMA.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_TYPE = 'BASE TABLE'
      AND (TABLE_NAME LIKE 'acc\_%' OR TABLE_NAME IN
           ('ref_account_type','ref_business','ref_voucher_type','ref_voucher_source',
            'ref_float_pattern','ref_float_movement_type'))
      AND TABLE_COLLATION <> 'utf8mb4_0900_ai_ci'
);
SET @failures = IF(@missing IS NULL, @failures, CONCAT(@failures, ' |COLLATION:', @missing));

-- ============================================================================
-- 3. Every foreign key, by name
-- ============================================================================
-- By NAME and not by count: Hibernate's substituted DDL would create none of
-- these, and a count could be satisfied by the wrong set.
SET @missing = (
    SELECT GROUP_CONCAT(x.name ORDER BY x.name)
    FROM (
        SELECT 'fk_acct_type' AS name
        UNION ALL SELECT 'fk_acct_business'  UNION ALL SELECT 'fk_acct_operator'
        UNION ALL SELECT 'fk_acct_pattern'
        UNION ALL SELECT 'fk_vch_type'       UNION ALL SELECT 'fk_vch_source'
        UNION ALL SELECT 'fk_vch_reverse'    UNION ALL SELECT 'fk_vch_creby'
        UNION ALL SELECT 'fk_line_voucher'   UNION ALL SELECT 'fk_line_account'
        UNION ALL SELECT 'fk_line_party'     UNION ALL SELECT 'fk_line_paymeth'
        UNION ALL SELECT 'fk_recon_account'  UNION ALL SELECT 'fk_recon_voucher'
        UNION ALL SELECT 'fk_recon_closedby'
        UNION ALL SELECT 'fk_fbatch_account' UNION ALL SELECT 'fk_fbatch_voucher'
        UNION ALL SELECT 'fk_fbatch_creby'
        UNION ALL SELECT 'fk_fmov_account'   UNION ALL SELECT 'fk_fmov_type'
        UNION ALL SELECT 'fk_fmov_batch'     UNION ALL SELECT 'fk_fmov_voucher'
        UNION ALL SELECT 'fk_fmov_recharge'  UNION ALL SELECT 'fk_fmov_rate'
        UNION ALL SELECT 'fk_rate_operator'  UNION ALL SELECT 'fk_rate_route'
        UNION ALL SELECT 'fk_rate_super'     UNION ALL SELECT 'fk_rate_creby'
    ) x
    WHERE NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
         WHERE TABLE_SCHEMA = DATABASE()
           AND CONSTRAINT_NAME = x.name AND CONSTRAINT_TYPE = 'FOREIGN KEY'
    )
);
SET @failures = IF(@missing IS NULL, @failures, CONCAT(@failures, ' |FK:', @missing));

-- ============================================================================
-- 4. Every CHECK, by name
-- ============================================================================
SET @missing = (
    SELECT GROUP_CONCAT(x.name ORDER BY x.name)
    FROM (
        SELECT 'ck_acctype_nb' AS name
        UNION ALL SELECT 'ck_acct_float'
        UNION ALL SELECT 'ck_line_nonneg'  UNION ALL SELECT 'ck_line_oneside'
        UNION ALL SELECT 'ck_line_nonzero'
        UNION ALL SELECT 'ck_fbatch_pos'
        UNION ALL SELECT 'ck_fmov_topup'   UNION ALL SELECT 'ck_fmov_nonzero'
        UNION ALL SELECT 'ck_rate_pct'     UNION ALL SELECT 'ck_rate_band'
        UNION ALL SELECT 'ck_rate_period'
    ) x
    WHERE NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS
         WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = x.name
    )
);
SET @failures = IF(@missing IS NULL, @failures, CONCAT(@failures, ' |CHECK:', @missing));

-- ============================================================================
-- 5. The three remaining triggers
-- ============================================================================
-- Reduced from seven on 2026-10-09. The four that went:
--   lines append-only, movements append-only  -> per-table GRANTs
--   reconciliation delete lock, rate immutability -> LedgerCorrectionRules
-- V23's header has the reasoning. These three are the only enforcement of the
-- CROSS-ROW balance invariant, which no grant can express.
--
-- V23 is now the ONLY migration that creates a trigger, so it is the only one
-- needing log_bin_trust_function_creators = 1 (or SUPER). V20's and V27's own
-- DELIMITER blocks create PROCEDUREs, which are measurably unaffected by that
-- variable -- verified 2026-10-09: CREATE PROCEDURE succeeds for a non-SUPER
-- account with binary logging on, while CREATE TRIGGER fails with ERROR 1419.
--
-- EXISTENCE IS NOT ENOUGH, and this gate cannot do better. A trigger that exists
-- but does not fire passes every assertion here. LedgerMigrationTest attempts
-- the bad writes and asserts each one is refused; that is the half of the proof
-- SQL cannot perform on itself.
--
-- ⚠ THE GRANTS ARE NOT ASSERTED HERE, and that is the honest cost of replacing
-- two triggers with them. A trigger is a schema guarantee this gate can verify;
-- a grant is environment configuration the repository cannot see, because the
-- account name differs per environment and the migrating account may legitimately
-- hold full privileges in development. db/migration/README.md carries the grants
-- and a verification query for the DBA to run, and LedgerMigrationTest proves the
-- mechanism works by building a restricted account and probing it.
SET @missing = (
    SELECT GROUP_CONCAT(x.name ORDER BY x.name)
    FROM (
        SELECT 'trg_acc_voucher_bu' AS name
        UNION ALL SELECT 'trg_acc_voucher_line_bi'
        UNION ALL SELECT 'trg_acc_voucher_bd'
    ) x
    WHERE NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.TRIGGERS
         WHERE TRIGGER_SCHEMA = DATABASE() AND TRIGGER_NAME = x.name
    )
);
SET @failures = IF(@missing IS NULL, @failures, CONCAT(@failures, ' |TRIGGER:', @missing));

-- And the four that were removed must be ABSENT, so a database that ran an
-- earlier build of V23/V24/V25 cannot sit in a half-converged state where the
-- rule is enforced twice and the service's error message never surfaces.
SET @missing = (
    SELECT GROUP_CONCAT(x.name ORDER BY x.name)
    FROM (
        SELECT 'trg_acc_voucher_line_bu' AS name
        UNION ALL SELECT 'trg_acc_voucher_line_bd'
        UNION ALL SELECT 'trg_acc_float_movement_bu'
        UNION ALL SELECT 'trg_acc_commission_rate_bu'
    ) x
    WHERE EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.TRIGGERS
         WHERE TRIGGER_SCHEMA = DATABASE() AND TRIGGER_NAME = x.name
    )
);
SET @failures = IF(@missing IS NULL, @failures, CONCAT(@failures, ' |STALETRIGGER:', @missing));

-- ============================================================================
-- 6. The column types that have already caused a defect
-- ============================================================================
-- OPERATOR_CODE at VARCHAR(20) on both tables: S1's V10 widened
-- rc_operator.OPERATOR_CODE from the design's VARCHAR(10) because the sentinel
-- codes MOBL_UNKNOWN / TVOPR_UNKNOWN are 12 and 13 characters and truncated
-- silently. A narrow child column here is errno 150.
--
-- The BIGINT keys: design-domain.md §8.6 records this repo being bitten by a
-- mediumint/int FK mismatch already.
--
-- RECHARGE_ID at int, because rc_recharge.RECHARGE_ID is int — not mediumint
-- like the legacy TXN_ID keys. Both mistakes are available and both are errno 150.
SET @missing = (
    SELECT GROUP_CONCAT(CONCAT(x.tbl, '.', x.col, ' is ',
                               COALESCE(c.COLUMN_TYPE, 'ABSENT'), ' not ', x.want)
                        ORDER BY x.tbl, x.col)
    FROM (
        SELECT 'acc_account'         AS tbl, 'OPERATOR_CODE' AS col, 'varchar(20)'   AS want
        UNION ALL SELECT 'acc_commission_rate', 'OPERATOR_CODE', 'varchar(20)'
        UNION ALL SELECT 'acc_account',         'ACCOUNT_CODE',  'varchar(30)'
        UNION ALL SELECT 'acc_voucher',         'VOUCHER_ID',    'bigint'
        UNION ALL SELECT 'acc_voucher_line',    'VOUCHER_ID',    'bigint'
        UNION ALL SELECT 'acc_voucher_line',    'LINE_ID',       'bigint'
        UNION ALL SELECT 'acc_voucher_line',    'DR_AMOUNT',     'decimal(38,2)'
        UNION ALL SELECT 'acc_voucher_line',    'CR_AMOUNT',     'decimal(38,2)'
        UNION ALL SELECT 'acc_float_movement',  'RECHARGE_ID',   'int'
        UNION ALL SELECT 'acc_float_movement',  'FACE_DELTA',    'decimal(38,2)'
        UNION ALL SELECT 'acc_float_movement',  'COST_DELTA',    'decimal(38,2)'
        UNION ALL SELECT 'acc_commission_rate', 'RATE_PCT',      'decimal(9,6)'
    ) x
    LEFT JOIN INFORMATION_SCHEMA.COLUMNS c
           ON c.TABLE_SCHEMA = DATABASE() AND c.TABLE_NAME = x.tbl AND c.COLUMN_NAME = x.col
    WHERE c.COLUMN_TYPE IS NULL OR c.COLUMN_TYPE <> x.want
);
SET @failures = IF(@missing IS NULL, @failures, CONCAT(@failures, ' |COLTYPE:', @missing));

-- ============================================================================
-- 7. The ledger is still empty
-- ============================================================================
-- The ledger must stay empty until the opening journal (log §12). A row here
-- before go-live means something posted that should not have.
SET @missing = (
    SELECT GROUP_CONCAT(CONCAT(x.tbl, '=', x.n) ORDER BY x.tbl)
    FROM (
        SELECT 'acc_voucher' AS tbl, (SELECT COUNT(*) FROM acc_voucher) AS n
        UNION ALL SELECT 'acc_voucher_line', (SELECT COUNT(*) FROM acc_voucher_line)
        UNION ALL SELECT 'acc_reconciliation', (SELECT COUNT(*) FROM acc_reconciliation)
        UNION ALL SELECT 'acc_float_batch', (SELECT COUNT(*) FROM acc_float_batch)
        UNION ALL SELECT 'acc_float_movement', (SELECT COUNT(*) FROM acc_float_movement)
    ) x
    WHERE x.n <> 0
);
SET @failures = IF(@missing IS NULL, @failures, CONCAT(@failures, ' |NOTEMPTY:', @missing));

-- ============================================================================
-- 8. The seeds landed
-- ============================================================================
-- 32 accounts = §2.2's 28 hand-seeded plus the four float wallets of log §2.4.
-- 12 rate rows = §4's 14 with IDEA and VDFN merged into VI by V20.
SET @missing = (
    SELECT GROUP_CONCAT(CONCAT(x.what, '=', x.got, ' want ', x.want) ORDER BY x.what)
    FROM (
        SELECT 'acc_account'              AS what, (SELECT COUNT(*) FROM acc_account) AS got, 32 AS want
        UNION ALL SELECT 'acc_account_float', (SELECT COUNT(*) FROM acc_account WHERE FLOAT_PATTERN IS NOT NULL), 4
        UNION ALL SELECT 'acc_commission_rate', (SELECT COUNT(*) FROM acc_commission_rate), 12
        UNION ALL SELECT 'ref_account_type', (SELECT COUNT(*) FROM ref_account_type), 5
        UNION ALL SELECT 'ref_business', (SELECT COUNT(*) FROM ref_business), 4
        UNION ALL SELECT 'ref_voucher_type', (SELECT COUNT(*) FROM ref_voucher_type), 6
        UNION ALL SELECT 'ref_voucher_source', (SELECT COUNT(*) FROM ref_voucher_source), 12
        UNION ALL SELECT 'ref_float_pattern', (SELECT COUNT(*) FROM ref_float_pattern), 3
        UNION ALL SELECT 'ref_float_movement_type', (SELECT COUNT(*) FROM ref_float_movement_type), 4
    ) x
    WHERE x.got <> x.want
);
SET @failures = IF(@missing IS NULL, @failures, CONCAT(@failures, ' |SEEDS:', @missing));

-- ============================================================================
-- 9. Cross-row rule: exactly one IS_POOL_PRIMARY per CASH_POOL (log §9.1)
-- ============================================================================
-- MySQL CHECK cannot express a cross-row rule, so it is a service check plus
-- this assertion — the precedent at design-domain.md:497. It matters because the
-- primary member absorbs its pool's whole short/over variance: zero primaries
-- means the variance has nowhere to go, two means it is double-counted.
SET @missing = (
    SELECT GROUP_CONCAT(CONCAT(x.CASH_POOL, ' has ', x.n, ' primaries') ORDER BY x.CASH_POOL)
    FROM (
        SELECT CASH_POOL, SUM(IS_POOL_PRIMARY) AS n
        FROM acc_account WHERE CASH_POOL IS NOT NULL GROUP BY CASH_POOL
    ) x
    WHERE x.n <> 1
);
SET @failures = IF(@missing IS NULL, @failures, CONCAT(@failures, ' |POOL:', @missing));

-- ============================================================================
-- 10. Rate coverage (log §8.2) — the three sound forms; see the header
-- ============================================================================

-- (a) Every active operator carries an open catch-all row. This is the hole
--     §8.2 actually cares about: an operator that can trade with no rate on file.
SET @missing = (
    SELECT GROUP_CONCAT(o.OPERATOR_CODE ORDER BY o.OPERATOR_CODE)
    FROM rc_operator o
    WHERE o.IS_ACTIVE = TRUE
      AND NOT EXISTS (
        SELECT 1 FROM acc_commission_rate r
         WHERE r.OPERATOR_CODE = o.OPERATOR_CODE
           AND r.PLAN_TIER = '*' AND r.MIN_AMOUNT = 0.00 AND r.MAX_AMOUNT IS NULL
           AND r.EFFECTIVE_TO IS NULL)
);
SET @failures = IF(@missing IS NULL, @failures,
                   CONCAT(@failures, ' |RATE-A:', @missing));

-- (b) Every open rate row points at something live. Catches a stale seed or a
--     typo that (a) cannot see.
SET @missing = (
    SELECT GROUP_CONCAT(CONCAT(r.OPERATOR_CODE, '/', r.ROUTE_CODE) ORDER BY r.OPERATOR_CODE, r.ROUTE_CODE)
    FROM acc_commission_rate r
    WHERE r.EFFECTIVE_TO IS NULL
      AND (NOT EXISTS (SELECT 1 FROM rc_operator o
                        WHERE o.OPERATOR_CODE = r.OPERATOR_CODE AND o.IS_ACTIVE = TRUE)
        OR (r.ROUTE_CODE <> '*'
            AND NOT EXISTS (SELECT 1 FROM ref_recharge_route rr
                             WHERE rr.CODE = r.ROUTE_CODE AND rr.IS_ACTIVE = TRUE)))
);
SET @failures = IF(@missing IS NULL, @failures,
                   CONCAT(@failures, ' |RATE-B:', @missing));

-- (c) Every open rate row's DERIVED float account exists and is active. This is
--     the assertion that guards V21's four-wallet derivation:
--         route = 'A1TOPUP' -> FLOAT_A1TOPUP
--         otherwise         -> CONCAT('FLOAT_', OPERATOR_CODE)
--     Add a rate for a new operator on DIRECT without creating its wallet and
--     this fails here rather than at the first recharge.
SET @missing = (
    SELECT GROUP_CONCAT(CONCAT(r.OPERATOR_CODE, '/', r.ROUTE_CODE, ' -> ',
                               IF(r.ROUTE_CODE = 'A1TOPUP', 'FLOAT_A1TOPUP',
                                  CONCAT('FLOAT_', r.OPERATOR_CODE)))
                        ORDER BY r.OPERATOR_CODE, r.ROUTE_CODE)
    FROM acc_commission_rate r
    WHERE r.EFFECTIVE_TO IS NULL
      AND r.ROUTE_CODE <> '*'
      AND NOT EXISTS (
        SELECT 1 FROM acc_account a
         WHERE a.ACCOUNT_CODE = IF(r.ROUTE_CODE = 'A1TOPUP', 'FLOAT_A1TOPUP',
                                   CONCAT('FLOAT_', r.OPERATOR_CODE))
           AND a.IS_ACTIVE = TRUE
           AND a.FLOAT_PATTERN IS NOT NULL)
);
SET @failures = IF(@missing IS NULL, @failures,
                   CONCAT(@failures, ' |RATE-C:', @missing));

-- ============================================================================
-- 11. Overlapping open rate rows (§4.2)
-- ============================================================================
-- MySQL has no exclusion constraint and uk_rate_key carries EFFECTIVE_FROM, so
-- two open rows can share a lookup key with different start dates. §4.2's
-- ORDER BY keeps the lookup deterministic, so this is a REPORT of a named
-- limitation rather than a corruption — but it should never be true of the
-- seeds, so the gate fails on it.
SET @missing = (
    SELECT GROUP_CONCAT(CONCAT(x.OPERATOR_CODE, '/', x.ROUTE_CODE, '/', x.PLAN_TIER,
                               ' x', x.n) ORDER BY x.OPERATOR_CODE, x.ROUTE_CODE)
    FROM (
        SELECT OPERATOR_CODE, ROUTE_CODE, PLAN_TIER, MIN_AMOUNT, COUNT(*) AS n
        FROM acc_commission_rate
        WHERE EFFECTIVE_TO IS NULL
        GROUP BY OPERATOR_CODE, ROUTE_CODE, PLAN_TIER, MIN_AMOUNT
    ) x
    WHERE x.n > 1
);
SET @failures = IF(@missing IS NULL, @failures, CONCAT(@failures, ' |OVERLAP:', @missing));

-- ============================================================================
-- 12. Signal, once, with everything
-- ============================================================================
-- ⚠ SIGNAL CANNOT BE RUN THROUGH PREPARE/EXECUTE — "ERROR 1295: This command is
-- not supported in the prepared statement protocol yet". The repo's dynamic-SQL
-- idiom therefore cannot carry an assertion, and the bug hides itself: the
-- passing branch prepares a harmless SELECT, so a gate written that way migrates
-- green and only reveals it cannot report on the day it has something to report.
-- A throwaway procedure signals conditionally outside a trigger and carries the
-- real message.
--
-- ⚠ MESSAGE_TEXT IS CAPPED AT 128 CHARACTERS and MySQL hard-errors above it with
-- "ERROR 1648: Data too long for condition item 'MESSAGE_TEXT'" rather than
-- truncating. So the signal is LEFT(...) to fit, and the full list is SELECTed
-- first — Flyway does not log that, but running this file by hand through the
-- mysql client prints it, which is what a human debugging a failed gate does.
-- The category prefixes are abbreviated for the same reason: TABLES, VIEW,
-- COLLATION, FK, CHECK, TRIGGER, STALETRIGGER, COLTYPE, NOTEMPTY, SEEDS, POOL,
-- RATE-A, RATE-B, RATE-C, OVERLAP.
DROP PROCEDURE IF EXISTS v27_assert;

DELIMITER $$
CREATE PROCEDURE v27_assert(IN p_failures TEXT)
BEGIN
    IF p_failures <> '' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = p_failures;
    END IF;
END$$
DELIMITER ;

SELECT IF(@failures = '', 'V27: all ledger schema assertions passed',
          CONCAT('V27 Gate 0 FAILED:', @failures)) AS gate_result;

CALL v27_assert(IF(@failures = '', '', LEFT(CONCAT('V27 Gate 0 FAILED:', @failures), 128)));
DROP PROCEDURE v27_assert;

SELECT 'V27: complete.' AS Status;
