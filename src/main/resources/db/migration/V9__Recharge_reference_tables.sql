-- ============================================================================
-- V9: Recharge Reference Tables, Settings, and Charset Alignment
-- ============================================================================
-- Spec: design-domain.md §6.3 (the V9 row), §4.7, §4.8, §3.1.
-- Creates the six ref_* code tables, rc_setting with its six seeds, and
-- converts the four utf8mb3 legacy tables to utf8mb4.
--
-- ALONGSIDE THE LEGACY WRITER (implementation-plan.md §0.2): no SEMANTIC change.
-- Every table created here is new and empty, and the charset conversions alter
-- no value and no column the legacy application reads or writes.
--
-- ⚠ BUT THIS MIGRATION IS NOT "PURELY ADDITIVE", AND THE PLAN SAYS IT IS.
-- implementation-plan.md:42 and next-session-s1.md:52 both describe V9-V12 as
-- "purely additive, all tables empty". That is true of V12 and of everything in
-- sections 1-3 below. It is NOT true of section 4: CONVERT TO CHARACTER SET
-- changes column types, so InnoDB cannot do it in place — it falls back to
-- ALGORITHM=COPY, rebuilding the table while holding an exclusive metadata
-- lock. The legacy writer is BLOCKED for the duration, not merely slowed.
--
-- Measured scale, so the window can be judged rather than guessed (restore,
-- 2026-10-08): rc_txn_details 6.5MB/99,038 rows, rc_txn_header 6.5MB/73,469,
-- rc_credit_req 0.3MB/4,301, rc_dishtv_dtls 0.1MB/458 — about 13MB in total.
-- All four conversions together ran in 0.34s in the migration test. So this is
-- a sub-second stall at today's volume, not an outage. It is still a stall:
-- RUN V9 IN A QUIET MINUTE, not mid-afternoon at the counter.
--
-- The conversion cannot simply be deferred — V12's DISHTV_NO foreign key and
-- V15's ref_company join both require it (§6.1 P0.6, §8.9). The honest summary
-- is "additive in effect, briefly blocking in execution".
--
-- Idempotent throughout, following the repo's V7 (CREATE TABLE IF NOT EXISTS)
-- and V3 (INFORMATION_SCHEMA-guarded ALTER) idioms.
-- ============================================================================

SELECT 'V9: creating recharge reference tables...' AS Status;

-- ============================================================================
-- 1. The six ref_* code tables (§4.8, in the V3:24-29 shape)
-- ============================================================================
-- IS_TERMINAL is carried only on the two status tables, where §5.1 makes it
-- load-bearing: the open-items queries read it declaratively rather than
-- hardcoding a status list that rots when a state is added.

CREATE TABLE IF NOT EXISTS ref_recharge_status (
    CODE        VARCHAR(20)  NOT NULL,
    DESCRIPTION VARCHAR(100) NOT NULL,
    IS_TERMINAL BOOLEAN      NOT NULL DEFAULT FALSE,
    CRE_DTTM    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (CODE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ref_payment_status (
    CODE        VARCHAR(20)  NOT NULL,
    DESCRIPTION VARCHAR(100) NOT NULL,
    IS_TERMINAL BOOLEAN      NOT NULL DEFAULT FALSE,
    CRE_DTTM    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (CODE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- VARCHAR(10) to match rc_txn_payment.LINE_TYPE (§4.3). MySQL will not create
-- an FK whose child and parent string columns differ in charset/collation, and
-- matching the declared length as well keeps the relationship obvious.
CREATE TABLE IF NOT EXISTS ref_payment_line_type (
    CODE        VARCHAR(10)  NOT NULL,
    DESCRIPTION VARCHAR(100) NOT NULL,
    CRE_DTTM    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (CODE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- VARCHAR(10) to match rc_recharge.RECHARGE_KIND and rc_operator.RECHARGE_KIND.
CREATE TABLE IF NOT EXISTS ref_recharge_kind (
    CODE        VARCHAR(10)  NOT NULL,
    DESCRIPTION VARCHAR(100) NOT NULL,
    CRE_DTTM    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (CODE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- §3.1 verbatim. Richer than the other ref_* tables on purpose: route
-- *availability* is data (label, active flag, sort order, retired date) while
-- route *behaviour* is the RechargeRoute enum. IS_TERMINAL is unused here and
-- present only for ref_* shape parity.
CREATE TABLE IF NOT EXISTS ref_recharge_route (
    CODE        VARCHAR(20)  NOT NULL,
    DESCRIPTION VARCHAR(100) NOT NULL,
    IS_ACTIVE   BOOLEAN      NOT NULL DEFAULT TRUE,
    IS_TERMINAL BOOLEAN      NOT NULL DEFAULT FALSE,
    SORT_ORDER  INT          NOT NULL DEFAULT 0,
    RETIRED_ON  DATE         NULL,
    CRE_DTTM    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (CODE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ref_credit_req_type (
    CODE        VARCHAR(10)  NOT NULL,
    DESCRIPTION VARCHAR(100) NOT NULL,
    CRE_DTTM    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (CODE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================================
-- 2. Seeds
-- ============================================================================
-- INSERT IGNORE rather than plain INSERT so a re-run is a no-op.

-- Recharge lifecycle (§4.8, §5.2).
--
-- FAILED is deliberately NOT terminal: "unresolved failures" is then simply
-- STATUS_CODE='FAILED' with no extra IS_RESOLVED flag, and resolving one moves
-- it to a terminal state.
--
-- COMPLETED is ALSO not terminal, per log §7.1. Two transitions out of it were
-- added — COMPLETED → VOIDED (the sale should not exist: wrong number,
-- duplicate, never happened) and COMPLETED → FAILED (marked complete but never
-- delivered, routed into the existing FAILED → RETRIED | REFUNDED machinery).
-- This matters beyond bookkeeping: the day's takings count rc_recharge rows
-- with STATUS_CODE='COMPLETED', so moving the status is what drops a bad sale
-- out of takings. A ledger-only correction would leave the row still saying
-- COMPLETED and the takings report still inflated.
--
-- v2 adds PENDING with IS_TERMINAL=0 and needs no schema change.
INSERT IGNORE INTO ref_recharge_status (CODE, DESCRIPTION, IS_TERMINAL) VALUES
    ('AWAITING_RECHARGE', 'Sale taken, recharge not yet performed', FALSE),
    ('COMPLETED',         'Recharge delivered',                     FALSE),
    ('FAILED',            'Recharge failed and is unresolved',      FALSE),
    ('VOIDED',            'Sale should not exist; reversed',        TRUE),
    ('RETRIED',           'Superseded by a retry attempt',          TRUE),
    ('REFUNDED',          'Refunded to the customer',               TRUE);

-- Payment lifecycle (§5.3). AWAITING_PAYMENT → SETTLED | CANCELLED.
INSERT IGNORE INTO ref_payment_status (CODE, DESCRIPTION, IS_TERMINAL) VALUES
    ('AWAITING_PAYMENT', 'Basket open, not yet paid for', FALSE),
    ('SETTLED',          'Paid for',                      TRUE),
    ('CANCELLED',        'Abandoned before payment',      TRUE);

INSERT IGNORE INTO ref_payment_line_type (CODE, DESCRIPTION) VALUES
    ('CASH', 'Cash tendered'),
    ('UPI',  'UPI / GPay transfer'),
    ('CRDT', 'Put on the customer''s tab');

INSERT IGNORE INTO ref_recharge_kind (CODE, DESCRIPTION) VALUES
    ('MOBILE', 'Mobile recharge'),
    ('TV',     'DTH / TV recharge');

-- Routes (§3.1, log §8.5).
--
-- A1TOPUP is seeded HERE, ACTIVE, and that is deliberate: V15's backfill maps
-- every SRVTE* row dated 2026-04-01 or later onto A1TOPUP, so the route must
-- already exist when V15 runs. The original plan had V16 insert it, which would
-- have failed V15's foreign key. log §8.5 and the §11.2 V9 row correct this.
--
-- SARAVATE is seeded ACTIVE even though it is a retired vendor, because V16 is
-- what retires it (IS_ACTIVE=0, RETIRED_ON=CURDATE()) after its gate passes.
-- Seeding it inactive here would make V16's UPDATE a silent no-op. Its
-- description records the stand-in usage: the vendor relationship ended but the
-- SRVTE* codes carried on being typed in as a stand-in for A1Topup, which is
-- why a flat '2024-01-01' retirement date would contradict live data — SRVTEB
-- carries 178 rows worth of 2026 activity, the most recent dated 2026-09-13.
INSERT IGNORE INTO ref_recharge_route (CODE, DESCRIPTION, IS_ACTIVE, SORT_ORDER) VALUES
    ('DIRECT',   'Direct',                                                      TRUE, 10),
    ('A1TOPUP',  'A1 Topup',                                                    TRUE, 20),
    ('SARAVATE', 'Saravate (retired vendor; codes later used for A1 Topup)',    TRUE, 30);

-- Credit request types (§4.5). Kept exactly as the legacy column uses them:
-- DBT is a debit to the tab (negative req_amt), DPAM a payment against it
-- (positive). A refund of a credit recharge writes a DPAM row, reducing the
-- tab rather than returning cash.
INSERT IGNORE INTO ref_credit_req_type (CODE, DESCRIPTION) VALUES
    ('DBT',  'Credit extended (debit to the tab)'),
    ('DPAM', 'Payment received against the tab');

-- ============================================================================
-- 3. rc_setting — requirement 10's global scalars (§4.7)
-- ============================================================================
CREATE TABLE IF NOT EXISTS rc_setting (
    SETTING_KEY   VARCHAR(60)  NOT NULL,
    SETTING_VALUE VARCHAR(255) NOT NULL,
    VALUE_TYPE    VARCHAR(10)  NOT NULL DEFAULT 'STRING',
    DESCRIPTION   VARCHAR(255) NULL,
    UPDATED_AT    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UPDATED_BY    INT          NULL,
    PRIMARY KEY (SETTING_KEY),
    CONSTRAINT fk_setting_updby FOREIGN KEY (UPDATED_BY)
        REFERENCES rc_user (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- SIX seeds. Earlier sections of the design mention three or four; these are
-- the complete set, with the two most recent added by the UX session.
--
-- ledger.opening.date is deliberately NOT seeded. It is written by
-- POST /accounts/opening-balances on go-live day, and its ABSENCE is how the
-- system knows the ledger has not been opened yet (log §12).
INSERT IGNORE INTO rc_setting (SETTING_KEY, SETTING_VALUE, VALUE_TYPE, DESCRIPTION) VALUES
    ('credit.warning.days', '30', 'INT',
     'Days before an outstanding credit balance is flagged as ageing'),

    ('eod.check.mode', 'WARN', 'STRING',
     'WARN or BLOCK: whether a failed end-of-day check stops the close'),

    -- Read at application startup, with 4 as the compiled-in fallback. April,
    -- not January: every report runs April-to-March off this value, which is
    -- also why V15 splits the SRVTE* routes at 2026-04-01 (log §8.7, §8.5).
    ('fy.start.month', '4', 'INT',
     'Financial year start month (4 = April)'),

    -- NO compiled-in fallback for this one, on purpose (log §9.3). A missing
    -- row is a configuration error that must fail loudly rather than quietly
    -- post to a guessed account. Named BANK_JANATA rather than a generic BANK
    -- because ACCOUNT_CODE is a natural key that never changes by design, so a
    -- generic code becomes permanently ambiguous the day a second account is
    -- added. The automatic entries (E6, E16, E19, E20) read the account from
    -- here, which is what makes a second bank account configuration rather
    -- than a code change.
    ('bank.default.account', 'BANK_JANATA', 'STRING',
     'ACCOUNT_CODE the UPI settlement credits and the float top-up debits'),

    -- The flat IMPS charge the shop bears on every float top-up, posted as its
    -- own expense leg rather than into the float's cost, which is what keeps
    -- the float comparable to the operator portal rupee for rupee
    -- (log §2.5, §14.9; design-ledger-api.md endpoint 19 defaults to this).
    ('float.imps.fee', '5.00', 'DECIMAL',
     'Flat IMPS fee per float top-up, in rupees'),

    -- Change below this is not shown at the counter. A rupee is not actually
    -- handed over — there is no coin and the customer waves it away — and ₹1
    -- alone accounts for 12,026 of the 13,223 sub-₹2 change rows. A setting
    -- rather than a UI constant so the figure can change without a release
    -- (log §14.5). Accepted consequence: the drawer runs over by roughly
    -- ₹1,000 a year, arriving at the close as cash over.
    ('change.display.threshold', '5.00', 'DECIMAL',
     'Change below this amount is not displayed at the counter');

-- ============================================================================
-- 4. Charset alignment — utf8mb3 → utf8mb4 (§6.1 P0.6, §8.9)
-- ============================================================================
-- These four tables are utf8mb3_general_ci; everything else in the schema is
-- utf8mb4_0900_ai_ci. Converting them now is a prerequisite, not tidying:
--
--   * V15's backfill joins rc_txn_details.ref_company against
--     rc_option_lookup_dtl.rc_option (already utf8mb4). Mixed collations in a
--     join raise "Illegal mix of collations" (errno 1267).
--   * V12's rc_recharge.DISHTV_NO foreign key points at
--     rc_dishtv_dtls.DISHTV_NO. MySQL refuses a string FK across differing
--     charsets (errno 150).
--
-- COLLATE is stated explicitly, not just CHARSET: a bare CHARSET=utf8mb4 can
-- resolve to utf8mb4_general_ci, which then will not FK against the rest of
-- the schema (design-ledger.md §8.2).
--
-- No value changes. utf8mb4 is a strict superset of utf8mb3, so every existing
-- byte sequence remains valid and compares identically. Guarded by the table's
-- current collation so a re-run is a no-op (the V3:315-326 idiom).

SET @sql = IF(
    (SELECT TABLE_COLLATION FROM INFORMATION_SCHEMA.TABLES
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rc_txn_header') <> 'utf8mb4_0900_ai_ci',
    'ALTER TABLE rc_txn_header CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci',
    'SELECT ''rc_txn_header already utf8mb4_0900_ai_ci'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT TABLE_COLLATION FROM INFORMATION_SCHEMA.TABLES
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rc_txn_details') <> 'utf8mb4_0900_ai_ci',
    'ALTER TABLE rc_txn_details CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci',
    'SELECT ''rc_txn_details already utf8mb4_0900_ai_ci'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT TABLE_COLLATION FROM INFORMATION_SCHEMA.TABLES
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rc_credit_req') <> 'utf8mb4_0900_ai_ci',
    'ALTER TABLE rc_credit_req CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci',
    'SELECT ''rc_credit_req already utf8mb4_0900_ai_ci'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT TABLE_COLLATION FROM INFORMATION_SCHEMA.TABLES
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rc_dishtv_dtls') <> 'utf8mb4_0900_ai_ci',
    'ALTER TABLE rc_dishtv_dtls CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci',
    'SELECT ''rc_dishtv_dtls already utf8mb4_0900_ai_ci'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT 'V9: complete.' AS Status;
