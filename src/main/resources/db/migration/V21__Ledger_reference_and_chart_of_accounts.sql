-- ============================================================================
-- V21: Ledger Reference Tables and Chart of Accounts
-- ============================================================================
-- Spec: design-ledger.md §2.1, §2.2 and §8.1's V20 row. It is V21 here, not
-- V20: see V20__Operator_catalog_vi_merge.sql's header for why the chain shifted.
--
-- SAFE ALONGSIDE THE LEGACY WRITER (implementation-plan.md §0.2): every table
-- created here is new and empty, and nothing the legacy application reads or
-- writes is altered. The ledger is a separate schema namespace.
--
-- COLLATION IS DECLARED EXPLICITLY ON EVERY TABLE, not just the charset
-- (design-ledger.md §8.2). A bare CHARSET=utf8mb4 can resolve to
-- utf8mb4_general_ci, and MySQL then refuses a foreign key against
-- rs_cust_dtls / rc_operator / ref_payment_line_type, which are all
-- utf8mb4_0900_ai_ci. That is errno 150 and it is entirely avoidable.
--
-- ⚠ TWO DEPARTURES FROM design-ledger.md §2.2, BOTH FORCED. Recorded here
-- because the file is what gets read when something looks wrong:
--
-- (1) acc_account.OPERATOR_CODE IS VARCHAR(20), NOT §8.2's VARCHAR(10).
--     It foreign-keys rc_operator.OPERATOR_CODE, which S1's V10 widened to
--     VARCHAR(20) because the design's own sentinel codes MOBL_UNKNOWN and
--     TVOPR_UNKNOWN are 12 and 13 characters and truncated silently at 10. V10's
--     header records the full story and names this migration as the first
--     downstream consequence. A mismatch here is errno 150. design-ledger.md:285
--     already assumes the full code — it computes CONCAT('FLOAT_','TVOPR_UNKNOWN')
--     as 19 characters — so §2.2 and S1 agree and §8.2 is the line that is wrong.
--
-- (2) FOUR FLOAT ACCOUNTS, HAND-LISTED, NOT ONE GENERATED PER OPERATOR.
--     design-ledger.md:275-282 generates one float account per active operator,
--     which against V10's catalogue is ten. recharge-technical-decisions.md
--     §2.4:84-105 decided otherwise and was never applied to that SQL — §11.1's
--     reconciliation pass carried the per-operator wording forward instead (its
--     §0 L1 row at :1456 still says "one float account per operator").
--
--     The shop holds FOUR wallets. §14.9:1973-1975 measures them: A1Topup
--     Rs 87,018, Jio Rs 66,568, Airtel Rs 66,239, Vi Rs 39,606. The ten-account
--     form produces six accounts for float the shop does not hold and omits the
--     largest one it does, because A1Topup is a ROUTE and no per-operator rule
--     can emit it. design-ledger-api.md:177 already posts to FLOAT_A1TOPUP by
--     name, so the four-wallet model is what the API design assumes too.
--
--     Generation is not merely wrong here, it is impossible: FLOAT_A1TOPUP
--     answers to six operators and has no single OPERATOR_CODE. Hand-listing
--     four rows loses §2.2's "adding an operator needs no ledger migration"
--     property, and that is the real cost — accepted, because a new operator
--     joins an EXISTING wallet (almost always A1Topup) rather than bringing its
--     own, so the common case needs no migration anyway.
--
--     The float account is resolved at runtime from operator and route together,
--     with no mapping table (confirmed with the owner 2026-10-08):
--
--         route = 'A1TOPUP'  ->  FLOAT_A1TOPUP
--         route = 'DIRECT'   ->  CONCAT('FLOAT_', OPERATOR_CODE)
--
--     That derivation is only total because V20 merged IDEA and VDFN into VI.
--     Two operator codes behind one wallet is the single case it cannot express.
--
--     Consequence for the schema: ck_acct_float INVERTS. §2.2 has
--     CHECK (FLOAT_PATTERN IS NULL OR OPERATOR_CODE IS NOT NULL) — "a float
--     pattern requires an operator" — which FLOAT_A1TOPUP violates. The
--     meaningful direction now is "an operator code requires a float pattern",
--     and the float discriminator moves from "OPERATOR_CODE IS NOT NULL" to
--     "FLOAT_PATTERN IS NOT NULL". The column, its FK and its index all stay:
--     three of the four wallets do map to exactly one operator, and FKing them
--     is what makes a mistyped code fail at migration time.
-- ============================================================================

SELECT 'V21: creating ledger reference tables...' AS Status;

-- ============================================================================
-- 1. The six reference tables (§2.1, in the V3:24-29 house lookup shape)
-- ============================================================================

CREATE TABLE IF NOT EXISTS ref_account_type (
    CODE            VARCHAR(12)  NOT NULL,
    DESCRIPTION     VARCHAR(100) NOT NULL,
    -- 'D' or 'C'. Sign logic becomes data rather than a Java switch, which is
    -- what lets the balance view and the P&L query share one CASE expression.
    NORMAL_BALANCE  CHAR(1)      NOT NULL,
    -- TRUE for INCOME/EXPENSE. The P&L query filters on IS_PL = TRUE and never
    -- on a hardcoded IN ('INCOME','EXPENSE') (§2.6).
    IS_PL           BOOLEAN      NOT NULL DEFAULT FALSE,
    SORT_ORDER      INT          NOT NULL DEFAULT 0,
    CRE_DTTM        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (CODE),
    CONSTRAINT ck_acctype_nb CHECK (NORMAL_BALANCE IN ('D','C'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ref_business (
    CODE        VARCHAR(10)  NOT NULL,
    DESCRIPTION VARCHAR(100) NOT NULL,
    IS_ACTIVE   BOOLEAN      NOT NULL DEFAULT TRUE,
    SORT_ORDER  INT          NOT NULL DEFAULT 0,
    CRE_DTTM    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (CODE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ref_voucher_type (
    CODE           VARCHAR(10)  NOT NULL,
    DESCRIPTION    VARCHAR(100) NOT NULL,
    -- FRD §6: which types fire from business events rather than from a screen.
    IS_AUTOMATIC   BOOLEAN      NOT NULL DEFAULT FALSE,
    -- FALSE for CONTRA, which FRD §6 says "never affects P&L".
    AFFECTS_PL     BOOLEAN      NOT NULL DEFAULT TRUE,
    SORT_ORDER     INT          NOT NULL DEFAULT 0,
    CRE_DTTM       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (CODE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ref_voucher_source (
    CODE         VARCHAR(20)  NOT NULL,
    DESCRIPTION  VARCHAR(100) NOT NULL,
    IS_AUTOMATIC BOOLEAN      NOT NULL DEFAULT FALSE,
    CRE_DTTM     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (CODE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ref_float_pattern (
    CODE        VARCHAR(12)  NOT NULL,
    DESCRIPTION VARCHAR(100) NOT NULL,
    CRE_DTTM    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (CODE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ref_float_movement_type (
    CODE        VARCHAR(12)  NOT NULL,
    DESCRIPTION VARCHAR(100) NOT NULL,
    CRE_DTTM    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (CODE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================================
-- 2. Reference seeds
-- ============================================================================
-- INSERT IGNORE rather than the INSERT ... SELECT ... WHERE NOT EXISTS form
-- §2.1 shows: same idempotency, and it matches what V9 settled on for this
-- repository's ref_* seeds.

-- EQUITY is a fifth account type that FRD §4 does not list (it names Asset,
-- Liability, Income, Expense). It is not optional: an opening-balance journal
-- cannot balance without somewhere to put the residual, and a balance sheet with
-- no capital line does not add up. Confirmed in log §2.2; flagged in §10 as
-- something the client should be told about, because it appears on the balance
-- sheet.
INSERT IGNORE INTO ref_account_type (CODE, DESCRIPTION, NORMAL_BALANCE, IS_PL, SORT_ORDER) VALUES
    ('ASSET',     'Asset',           'D', FALSE, 10),
    ('LIABILITY', 'Liability',       'C', FALSE, 20),
    ('EQUITY',    'Owner''s equity', 'C', FALSE, 30),
    ('INCOME',    'Income',          'C', TRUE,  40),
    ('EXPENSE',   'Expense',         'D', TRUE,  50);

-- SHARED is a real business code, not a null object: Bank, UPI Clearing,
-- Cash-Safe and the reconciliation variance accounts belong to no single
-- business, and L3 puts business scoping on the account rather than the voucher.
-- CROCKERY joins in phase 2.
INSERT IGNORE INTO ref_business (CODE, DESCRIPTION, IS_ACTIVE, SORT_ORDER) VALUES
    ('WATER',    'Water',                  TRUE, 10),
    ('RECHARGE', 'Recharge',               TRUE, 20),
    ('TICKET',   'Ticket booking',         TRUE, 30),
    ('SHARED',   'Shared / whole shop',    TRUE, 40);

-- PURCHASE is marked automatic = FALSE: the float top-up it exists for is a
-- user-driven POST (design-ledger-api.md endpoint 19, log §14.9), not an event.
INSERT IGNORE INTO ref_voucher_type (CODE, DESCRIPTION, IS_AUTOMATIC, AFFECTS_PL, SORT_ORDER) VALUES
    ('SALES',    'Sale',                      TRUE,  TRUE,  10),
    ('PURCHASE', 'Purchase',                  FALSE, TRUE,  20),
    ('RECEIPT',  'Money received',            TRUE,  TRUE,  30),
    ('PAYMENT',  'Money paid out',            FALSE, TRUE,  40),
    ('CONTRA',   'Transfer between own accounts', FALSE, FALSE, 50),
    ('JOURNAL',  'Correction or adjustment',  FALSE, TRUE,  60);

INSERT IGNORE INTO ref_voucher_source (CODE, DESCRIPTION, IS_AUTOMATIC) VALUES
    ('RECHARGE_SALE',  'Recharge sale',                        TRUE),
    ('WATER_TRIP',     'Water trip completed',                 TRUE),
    ('WATER_RECEIPT',  'Water payment received',               TRUE),
    ('FLOAT_TOPUP',    'Operator float top-up',                FALSE),
    ('CREDIT_RECEIPT', 'Payment against a customer tab',       TRUE),
    ('REFUND',         'Refund or float reversal',             TRUE),
    ('UPI_SETTLEMENT', 'UPI settled into the bank',            FALSE),
    ('CASH_RECON',     'Cash count variance',                  FALSE),
    ('CORRECTION',     'Correcting journal (FRD 8.3)',         FALSE),
    ('OPENING',        'Opening balances at go-live',          FALSE),
    ('MANUAL',         'Hand-entered voucher',                 FALSE),
    ('TICKET_BOOKING', 'Rail or bus ticket booking',           TRUE);

-- All four of the shop's floats are FRD §7.2 pattern 2 (log §2.4:97-99): pay
-- Rs X, receive exactly Rs X of face value, margin from a per-transaction rate.
-- So cost basis equals face value and the ratio is exactly 1.0. BONUS_TOPUP and
-- BOTH are seeded because §3.3's single formula covers them at no cost, not
-- because anything uses them — §3.5 calls that machinery dormant, not dead.
INSERT IGNORE INTO ref_float_pattern (CODE, DESCRIPTION) VALUES
    ('BONUS_TOPUP', 'Bonus face value on top-up (FRD 7.2.1)'),
    ('TXN_RATE',    'Per-transaction commission rate (FRD 7.2.2)'),
    ('BOTH',        'Bonus top-up and a per-transaction rate');

INSERT IGNORE INTO ref_float_movement_type (CODE, DESCRIPTION) VALUES
    ('TOPUP',    'Float purchased'),
    ('SALE',     'Float consumed by a recharge'),
    ('REVERSAL', 'Float returned by a failed or voided recharge'),
    ('ADJUST',   'Reconciliation adjustment');

-- ============================================================================
-- 3. acc_account — the chart of accounts (§2.2)
-- ============================================================================
SELECT 'V21: creating the chart of accounts...' AS Status;

CREATE TABLE IF NOT EXISTS acc_account (
    -- Natural key. Thirty-odd rows that never change identity, and a natural
    -- code makes every migration, posting rule and hand-written report readable
    -- (WHERE ACCOUNT_CODE = 'CASH_RECHARGE', not WHERE ACCOUNT_ID = 4).
    -- DISPLAY_NAME carries the label, so the usual renaming objection does not
    -- apply. Cost: 30 bytes per voucher line instead of 4, ~450 KB/year.
    ACCOUNT_CODE     VARCHAR(30)  NOT NULL,
    DISPLAY_NAME     VARCHAR(80)  NOT NULL,
    ACCOUNT_TYPE     VARCHAR(12)  NOT NULL,
    BUSINESS_CODE    VARCHAR(10)  NOT NULL,
    -- Flat balance-sheet subtotal label. Buys the one thing a hierarchy would
    -- have bought, for one nullable varchar and no recursive CTE.
    REPORT_GROUP     VARCHAR(30)  NULL,
    -- VARCHAR(20), not §8.2's VARCHAR(10) — see departure (1) in the header.
    -- NULL on FLOAT_A1TOPUP, which answers to six operators.
    OPERATOR_CODE    VARCHAR(20)  NULL,
    -- Non-NULL is what makes this a float account; see departure (2).
    FLOAT_PATTERN    VARCHAR(12)  NULL,
    IS_RECONCILABLE  BOOLEAN      NOT NULL DEFAULT FALSE,
    -- FRD §6's short pre-configured picker list. A7: FALSE keeps an account out
    -- of the GENERIC voucher endpoints, not out of the ledger — the float
    -- accounts, UPI_CLEARING and RCHG_PENDING are machine-owned.
    ALLOW_MANUAL     BOOLEAN      NOT NULL DEFAULT TRUE,
    REQUIRES_PARTY   BOOLEAN      NOT NULL DEFAULT FALSE,
    -- POOL-1 | POOL-2 | SAFE; NULL for everything not physically counted
    -- (log §3, §9.1). Counting is per drawer, which does not follow business
    -- lines, so this grouping cannot be derived from BUSINESS_CODE.
    CASH_POOL        VARCHAR(10)  NULL,
    -- The pool member that absorbs its whole short/over variance (log §9.1).
    IS_POOL_PRIMARY  BOOLEAN      NOT NULL DEFAULT FALSE,
    IS_ACTIVE        BOOLEAN      NOT NULL DEFAULT TRUE,
    SORT_ORDER       INT          NOT NULL DEFAULT 0,
    NOTE             VARCHAR(255) NULL,
    CRE_DTTM         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UPD_DTTM         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (ACCOUNT_CODE),
    CONSTRAINT fk_acct_type     FOREIGN KEY (ACCOUNT_TYPE)  REFERENCES ref_account_type (CODE),
    CONSTRAINT fk_acct_business FOREIGN KEY (BUSINESS_CODE) REFERENCES ref_business (CODE),
    CONSTRAINT fk_acct_operator FOREIGN KEY (OPERATOR_CODE) REFERENCES rc_operator (OPERATOR_CODE),
    CONSTRAINT fk_acct_pattern  FOREIGN KEY (FLOAT_PATTERN) REFERENCES ref_float_pattern (CODE),
    -- INVERTED from §2.2 — see departure (2). "An operator code requires a float
    -- pattern", which keeps a non-float account from carrying an operator while
    -- allowing FLOAT_A1TOPUP's NULL operator.
    CONSTRAINT ck_acct_float    CHECK (OPERATOR_CODE IS NULL OR FLOAT_PATTERN IS NOT NULL),
    -- FRD §8.4's three groupings: per-business P&L, combined P&L, balance sheet.
    KEY idx_acct_type_business (ACCOUNT_TYPE, BUSINESS_CODE, SORT_ORDER),
    KEY idx_acct_business      (BUSINESS_CODE, ACCOUNT_TYPE),
    KEY idx_acct_recon         (IS_RECONCILABLE, IS_ACTIVE),
    KEY idx_acct_operator      (OPERATOR_CODE),
    KEY idx_acct_pool          (CASH_POOL, IS_POOL_PRIMARY)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================================
-- 4. The 28 seeded accounts (§2.2)
-- ============================================================================
-- FRD §5's nineteen, plus the nine log §11.1 adds and the one it renames:
-- CASH_SAFE (§3), BANK_JANATA replacing a generic BANK (§9.3), BANK_FATHER
-- (§10.6), ADV_RECHARGE (§2.8), RCHG_PENDING (§7.2), DRAWINGS (§2.2),
-- INC_RCHG_SURCHARGE (§10.2), EXP_BANK_CHARGES (§2.5), EXP_RCHG_LOSS (§7.3),
-- EXP_SUNDRY_SHOP (§9.1), INC_CASH_OVER / EXP_CASH_SHORT (§9.1) and
-- OWNERS_EQUITY (§2.2).
--
-- BANK is deliberately NOT seeded as 'BANK' (log §9.3). ACCOUNT_CODE never
-- changes by design, so a generic code would mean BANK silently meant Janata
-- forever once a second account was added. The automatic postings resolve the
-- bank from rc_setting['bank.default.account'], which V9 already seeded to
-- BANK_JANATA — that is what makes a second bank account a configuration change
-- rather than a code change.
--
-- ALLOW_MANUAL = FALSE on UPI_CLEARING, RCHG_PENDING and the AR_*/ADV_* accounts
-- per A7: they are moved by the endpoints that own them, never by a hand-posted
-- leg through the generic journal.
INSERT INTO acc_account (ACCOUNT_CODE, DISPLAY_NAME, ACCOUNT_TYPE, BUSINESS_CODE, REPORT_GROUP,
                         IS_RECONCILABLE, ALLOW_MANUAL, REQUIRES_PARTY, SORT_ORDER)
SELECT src.* FROM (
  SELECT 'CASH_WATER' AS ACCOUNT_CODE, 'Cash-Water' AS DISPLAY_NAME, 'ASSET' AS ACCOUNT_TYPE, 'WATER' AS BUSINESS_CODE, 'CASH_AND_BANK' AS REPORT_GROUP, TRUE AS IS_RECONCILABLE, TRUE AS ALLOW_MANUAL, FALSE AS REQUIRES_PARTY, 100 AS SORT_ORDER
  UNION ALL SELECT 'CASH_RECHARGE',       'Cash-Recharge',                     'ASSET',     'RECHARGE', 'CASH_AND_BANK', TRUE,  TRUE,  FALSE, 110
  UNION ALL SELECT 'CASH_TICKET',         'Cash-Ticket',                       'ASSET',     'TICKET',   'CASH_AND_BANK', TRUE,  TRUE,  FALSE, 120
  UNION ALL SELECT 'CASH_SAFE',           'Cash-Safe',                         'ASSET',     'SHARED',   'CASH_AND_BANK', TRUE,  TRUE,  FALSE, 125
  UNION ALL SELECT 'BANK_JANATA',         'Janata Sahakari Bank',              'ASSET',     'SHARED',   'CASH_AND_BANK', TRUE,  TRUE,  FALSE, 130
  UNION ALL SELECT 'BANK_SELF',           'Bank-Self',                         'ASSET',     'SHARED',   'CASH_AND_BANK', TRUE,  TRUE,  FALSE, 140
  UNION ALL SELECT 'BANK_WIFE',           'Bank-Wife',                         'ASSET',     'SHARED',   'CASH_AND_BANK', TRUE,  TRUE,  FALSE, 150
  UNION ALL SELECT 'BANK_FATHER',         'Bank-Father (SBI)',                 'ASSET',     'SHARED',   'CASH_AND_BANK', TRUE,  TRUE,  FALSE, 155
  UNION ALL SELECT 'UPI_CLEARING',        'UPI Clearing',                      'ASSET',     'SHARED',   'CASH_AND_BANK', TRUE,  FALSE, FALSE, 160
  UNION ALL SELECT 'AR_WATER',            'Accounts Receivable - Water',       'ASSET',     'WATER',    'RECEIVABLES',   FALSE, FALSE, TRUE,  200
  UNION ALL SELECT 'AR_RECHARGE',         'Accounts Receivable - Recharge',    'ASSET',     'RECHARGE', 'RECEIVABLES',   FALSE, FALSE, TRUE,  210
  UNION ALL SELECT 'AR_TICKET',           'Accounts Receivable - Ticket',      'ASSET',     'TICKET',   'RECEIVABLES',   FALSE, FALSE, TRUE,  220
  UNION ALL SELECT 'ADV_WATER',           'Customer Advance - Water',          'LIABILITY', 'WATER',    'ADVANCES',      FALSE, FALSE, TRUE,  300
  UNION ALL SELECT 'ADV_RECHARGE',        'Customer Advance - Recharge',       'LIABILITY', 'RECHARGE', 'ADVANCES',      FALSE, FALSE, TRUE,  310
  UNION ALL SELECT 'RCHG_PENDING',        'Recharge Pending - Customer',       'LIABILITY', 'RECHARGE', 'CUSTOMER_HELD', FALSE, FALSE, FALSE, 320
  UNION ALL SELECT 'OWNERS_EQUITY',       'Owner''s Capital',                  'EQUITY',    'SHARED',   'EQUITY',        FALSE, FALSE, FALSE, 400
  UNION ALL SELECT 'DRAWINGS',            'Drawings',                          'EQUITY',    'SHARED',   'EQUITY',        FALSE, TRUE,  FALSE, 410
  UNION ALL SELECT 'INC_WATER_TRIP',      'Water Trip Income',                 'INCOME',    'WATER',    'INCOME',        FALSE, FALSE, FALSE, 500
  UNION ALL SELECT 'INC_RCHG_COMM',       'Recharge Commission Income',        'INCOME',    'RECHARGE', 'INCOME',        FALSE, FALSE, FALSE, 510
  UNION ALL SELECT 'INC_RCHG_SURCHARGE',  'Recharge Surcharge Income',         'INCOME',    'RECHARGE', 'INCOME',        FALSE, FALSE, FALSE, 515
  UNION ALL SELECT 'INC_TKT_COMM',        'Ticket Booking Commission Income',  'INCOME',    'TICKET',   'INCOME',        FALSE, FALSE, FALSE, 520
  UNION ALL SELECT 'INC_CASH_OVER',       'Cash Over (reconciliation)',        'INCOME',    'SHARED',   'INCOME',        FALSE, FALSE, FALSE, 590
  UNION ALL SELECT 'EXP_ELEC_WATER',      'Electricity - Water',               'EXPENSE',   'WATER',    'EXPENSE',       FALSE, TRUE,  FALSE, 600
  UNION ALL SELECT 'EXP_MAINT_WATER',     'Maintenance - Water',               'EXPENSE',   'WATER',    'EXPENSE',       FALSE, TRUE,  FALSE, 610
  UNION ALL SELECT 'EXP_BANK_CHARGES',    'Bank Charges - Recharge',           'EXPENSE',   'RECHARGE', 'EXPENSE',       FALSE, TRUE,  FALSE, 620
  UNION ALL SELECT 'EXP_RCHG_LOSS',       'Recharge Losses - Recharge',        'EXPENSE',   'RECHARGE', 'EXPENSE',       FALSE, TRUE,  FALSE, 630
  UNION ALL SELECT 'EXP_SUNDRY_SHOP',     'Sundry Expenses - Shop',            'EXPENSE',   'SHARED',   'EXPENSE',       FALSE, TRUE,  FALSE, 640
  UNION ALL SELECT 'EXP_CASH_SHORT',      'Cash Short (reconciliation)',       'EXPENSE',   'SHARED',   'EXPENSE',       FALSE, FALSE, FALSE, 690
) AS src
WHERE NOT EXISTS (SELECT 1 FROM acc_account a WHERE a.ACCOUNT_CODE = src.ACCOUNT_CODE);

-- ============================================================================
-- 5. The cash pools (log §3, §9.1)
-- ============================================================================
-- Counting is per physical drawer, which does not follow the business lines:
-- CASH_RECHARGE and CASH_TICKET share one. Exactly one member per pool carries
-- IS_POOL_PRIMARY, and that member absorbs the pool's whole short/over variance.
--
-- MySQL CHECK cannot express "one per pool" — it is a cross-row rule. It is a
-- service check plus a V27 assertion, following the precedent at
-- design-domain.md:497.
--
-- The variance member is a formality rather than an attribution: both variance
-- accounts are BUSINESS_CODE='SHARED', so no business's P&L moves whichever
-- member is picked, and the tills sweep to CASH_SAFE nightly anyway (log §9.1).
UPDATE acc_account SET CASH_POOL = 'POOL-1', IS_POOL_PRIMARY = TRUE  WHERE ACCOUNT_CODE = 'CASH_RECHARGE';
UPDATE acc_account SET CASH_POOL = 'POOL-1', IS_POOL_PRIMARY = FALSE WHERE ACCOUNT_CODE = 'CASH_TICKET';
UPDATE acc_account SET CASH_POOL = 'POOL-2', IS_POOL_PRIMARY = TRUE  WHERE ACCOUNT_CODE = 'CASH_WATER';
UPDATE acc_account SET CASH_POOL = 'SAFE',   IS_POOL_PRIMARY = TRUE  WHERE ACCOUNT_CODE = 'CASH_SAFE';
-- CASH_CROCKERY joins POOL-2 as a non-primary member in phase 2. Until it
-- exists, POOL-2 cannot fully reconcile: crockery cash is already in that drawer
-- but no crockery sale is recorded (log §3).

-- ============================================================================
-- 6. The four float accounts (log §2.4, §14.9)
-- ============================================================================
-- Hand-listed, not generated — see departure (2) in the header. Four wallets,
-- measured at §14.9:1973-1975 by FY2026-27 consumption:
--
--     A1Topup  Rs 87,018      FLOAT_A1TOPUP   (a ROUTE; six operators)
--     Jio      Rs 66,568      FLOAT_RJIO
--     Airtel   Rs 66,239      FLOAT_ARTL
--     Vi       Rs 39,606      FLOAT_VI        (V20 merged IDEA + VDFN)
--
-- All four are FRD §7.2 pattern 2, so FLOAT_PATTERN = 'TXN_RATE' throughout and
-- the cost/face ratio is exactly 1.0 (log §2.4:97-99). That is what makes the
-- portal-versus-books comparison a straight one, and it is why §2.5 keeps the
-- Rs 5 IMPS fee out of the float's cost basis.
--
-- IS_RECONCILABLE = TRUE: each wallet has a portal balance a human reads and
-- types in (log §14.9:1965-1967 — no vendor API exists or is expected).
-- ALLOW_MANUAL = FALSE per A7: a top-up goes through the Purchase endpoint and a
-- correction through the float close, never the generic journal.
--
-- OPERATOR_CODE is set for the three single-operator wallets so a mistyped code
-- fails here at errno 150 rather than at the first recharge; NULL for
-- FLOAT_A1TOPUP, which no single operator owns.
INSERT INTO acc_account (ACCOUNT_CODE, DISPLAY_NAME, ACCOUNT_TYPE, BUSINESS_CODE, REPORT_GROUP,
                         OPERATOR_CODE, FLOAT_PATTERN, IS_RECONCILABLE, ALLOW_MANUAL, SORT_ORDER, NOTE)
SELECT src.* FROM (
  SELECT 'FLOAT_ARTL' AS ACCOUNT_CODE, 'Airtel Float' AS DISPLAY_NAME, 'ASSET' AS ACCOUNT_TYPE, 'RECHARGE' AS BUSINESS_CODE, 'FLOAT' AS REPORT_GROUP, 'ARTL' AS OPERATOR_CODE, 'TXN_RATE' AS FLOAT_PATTERN, TRUE AS IS_RECONCILABLE, FALSE AS ALLOW_MANUAL, 900 AS SORT_ORDER, 'Direct Airtel wallet' AS NOTE
  UNION ALL SELECT 'FLOAT_RJIO',    'Jio Float',     'ASSET', 'RECHARGE', 'FLOAT', 'RJIO', 'TXN_RATE', TRUE, FALSE, 910, 'Direct Jio wallet'
  UNION ALL SELECT 'FLOAT_VI',      'Vi Float',      'ASSET', 'RECHARGE', 'FLOAT', 'VI',   'TXN_RATE', TRUE, FALSE, 920, 'Direct Vi wallet; covers the former IDEA and VDFN codes (log 2.4)'
  UNION ALL SELECT 'FLOAT_A1TOPUP', 'A1Topup Float', 'ASSET', 'RECHARGE', 'FLOAT', NULL,   'TXN_RATE', TRUE, FALSE, 930, 'Aggregator wallet; a ROUTE, not an operator. Covers BSNL and all five TV operators (log 2.4)'
) AS src
WHERE NOT EXISTS (SELECT 1 FROM acc_account a WHERE a.ACCOUNT_CODE = src.ACCOUNT_CODE);

SELECT 'V21: complete.' AS Status;
