-- ============================================================================
-- V11: Payment Header Extensions
-- ============================================================================
-- Spec: design-domain.md §6.3 (the V11 row), §4.1, §4.2, §7.2.
--
-- rc_txn_header is REUSED, not replaced: it stays the basket/payment header
-- and gains three columns — a payment status, staff attribution, and the named
-- customer a credit tender requires.
--
-- ALONGSIDE THE LEGACY WRITER: all three columns are additive, and STATUS_CODE
-- carries a DEFAULT so the legacy application's INSERTs — which name neither it
-- nor the other two — keep working unchanged. Two points were raised against
-- this and are worth recording with their evidence, because both look alarming
-- and only one of them is real:
--
--   * "A legacy header inserted after the ALTER gets a NULL STATUS_CODE, or the
--     insert fails outright." It does not. The column is NOT NULL DEFAULT
--     'SETTLED', so an INSERT that omits it takes the default. That is the whole
--     reason the default exists rather than a separate UPDATE pass.
--
--   * "The new UNIQUE(SEQ_NO) can reject the legacy app's next multi-line
--     basket." It cannot. SEQ_NO is AUTO_INCREMENT and allocated GLOBALLY, not
--     per basket: measured 2026-10-08, it runs 1..99,050 over 99,038 rows with
--     99,038 distinct values (the 12 gaps are deletions), while the largest
--     single basket holds 12 lines. A multi-line basket receives twelve
--     distinct global values, and a UNIQUE constraint cannot be violated by the
--     server handing out its own next number.
--
-- What IS true is that these are ALTERs on a live table and therefore take a
-- metadata lock. Adding a column with a default and adding secondary indexes
-- are both ALGORITHM=INPLACE in InnoDB, so the lock is brief and concurrent DML
-- continues — unlike V9's charset conversion, which genuinely blocks. See V9's
-- header.
--
-- ⚠ THIS MIGRATION DOES NOT TOUCH MONEY. The semantics change described in
-- §4.1 (amt_tndred / amt_returned always >= 0) and its CHECK constraint belong
-- to V13/V14, which are cutover-day work and must not run here.
-- ============================================================================

SELECT 'V11: extending the payment header...' AS Status;

-- ============================================================================
-- 1. STATUS_CODE (§4.1)
-- ============================================================================
-- NOT NULL DEFAULT 'SETTLED' is what backfills history in one step: every one
-- of the 73,469 existing headers is a completed sale, so adding the column
-- with this default IS the backfill — no separate UPDATE pass, and no window
-- in which the column is nullable.
--
-- The default also keeps the legacy writer correct: it inserts headers without
-- naming STATUS_CODE, and a legacy sale is settled the moment it is written.
-- The new counter sets the value explicitly, including AWAITING_PAYMENT.
SET @col_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'rc_txn_header' AND COLUMN_NAME = 'STATUS_CODE'
);
SET @sql = IF(
    @col_exists = 0,
    'ALTER TABLE rc_txn_header
        ADD COLUMN STATUS_CODE VARCHAR(20) NOT NULL DEFAULT ''SETTLED''',
    'SELECT ''rc_txn_header.STATUS_CODE exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================================
-- 2. CRE_BY — staff attribution (§4.1, §8.8)
-- ============================================================================
-- Nullable, and it STAYS NULL for all 73,469 historical rows: no record of who
-- served a legacy sale exists anywhere, so there is nothing to backfill from.
-- Risk R5 is exactly this — the staff filter on the history screen cannot
-- return pre-cutover sales, and that is a known limitation rather than a bug.
--
-- ON DELETE SET NULL: removing a user must not delete the book of record.
SET @col_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'rc_txn_header' AND COLUMN_NAME = 'CRE_BY'
);
SET @sql = IF(
    @col_exists = 0,
    'ALTER TABLE rc_txn_header ADD COLUMN CRE_BY INT NULL',
    'SELECT ''rc_txn_header.CRE_BY exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================================
-- 3. CUST_ID — the named customer (§4.1)
-- ============================================================================
-- Nullable at the database level, but REQUIRED whenever a CRDT tender exists
-- (requirement 7). That rule spans two tables — rc_txn_payment.LINE_TYPE='CRDT'
-- implies rc_txn_header.CUST_ID IS NOT NULL — which a MySQL CHECK cannot
-- express. §5.3 decides it deliberately: enforce it in the service plus a
-- nightly reconciliation query, NOT a BEFORE INSERT trigger, because this
-- codebase has no triggers today and adding an invisible one to the book of
-- record is a maintenance trap. The honest cost: hand-written SQL can break
-- this rule.
SET @col_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'rc_txn_header' AND COLUMN_NAME = 'CUST_ID'
);
SET @sql = IF(
    @col_exists = 0,
    'ALTER TABLE rc_txn_header ADD COLUMN CUST_ID INT NULL',
    'SELECT ''rc_txn_header.CUST_ID exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================================
-- 4. The three foreign keys
-- ============================================================================
-- Added after all three columns exist. rc_txn_header is utf8mb4_0900_ai_ci as
-- of V9, which is what makes the STATUS_CODE key possible at all — against the
-- old utf8mb3_general_ci it would have failed with errno 150.

SET @fk_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rc_txn_header'
      AND CONSTRAINT_NAME = 'fk_txnh_status' AND CONSTRAINT_TYPE = 'FOREIGN KEY'
);
SET @sql = IF(
    @fk_exists = 0,
    'ALTER TABLE rc_txn_header
        ADD CONSTRAINT fk_txnh_status FOREIGN KEY (STATUS_CODE)
            REFERENCES ref_payment_status (CODE)',
    'SELECT ''fk_txnh_status exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @fk_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rc_txn_header'
      AND CONSTRAINT_NAME = 'fk_txnh_creby' AND CONSTRAINT_TYPE = 'FOREIGN KEY'
);
SET @sql = IF(
    @fk_exists = 0,
    'ALTER TABLE rc_txn_header
        ADD CONSTRAINT fk_txnh_creby FOREIGN KEY (CRE_BY)
            REFERENCES rc_user (id) ON DELETE SET NULL',
    'SELECT ''fk_txnh_creby exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @fk_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rc_txn_header'
      AND CONSTRAINT_NAME = 'fk_txnh_cust' AND CONSTRAINT_TYPE = 'FOREIGN KEY'
);
SET @sql = IF(
    @fk_exists = 0,
    'ALTER TABLE rc_txn_header
        ADD CONSTRAINT fk_txnh_cust FOREIGN KEY (CUST_ID)
            REFERENCES rs_cust_dtls (CUST_ID)',
    'SELECT ''fk_txnh_cust exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================================
-- 5. Header indexes (§7.2)
-- ============================================================================
-- Every screen in the design filters headers by date, so TXN_DTTM leads three
-- of these four. The composites are not redundant against idx_txnh_dttm: the
-- open-baskets query filters status first, and the history screen's staff
-- filter needs CRE_BY before the date range.
SET @idx_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rc_txn_header'
      AND INDEX_NAME = 'idx_txnh_dttm'
);
SET @sql = IF(@idx_exists = 0,
    'ALTER TABLE rc_txn_header ADD KEY idx_txnh_dttm (TXN_DTTM)',
    'SELECT ''idx_txnh_dttm exists'' AS Info');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rc_txn_header'
      AND INDEX_NAME = 'idx_txnh_status_dttm'
);
SET @sql = IF(@idx_exists = 0,
    'ALTER TABLE rc_txn_header ADD KEY idx_txnh_status_dttm (STATUS_CODE, TXN_DTTM)',
    'SELECT ''idx_txnh_status_dttm exists'' AS Info');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rc_txn_header'
      AND INDEX_NAME = 'idx_txnh_creby_dttm'
);
SET @sql = IF(@idx_exists = 0,
    'ALTER TABLE rc_txn_header ADD KEY idx_txnh_creby_dttm (CRE_BY, TXN_DTTM)',
    'SELECT ''idx_txnh_creby_dttm exists'' AS Info');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rc_txn_header'
      AND INDEX_NAME = 'idx_txnh_cust'
);
SET @sql = IF(@idx_exists = 0,
    'ALTER TABLE rc_txn_header ADD KEY idx_txnh_cust (CUST_ID)',
    'SELECT ''idx_txnh_cust exists'' AS Info');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================================
-- 6. UNIQUE KEY on rc_txn_details.SEQ_NO (§4.2, §8.5)
-- ============================================================================
-- Verified immediately before writing this migration: 99,038 detail rows,
-- 99,038 distinct SEQ_NO, zero collisions. (§6.3 quotes 99,016/99,016 from the
-- 2026-09-15 dump; the restore is five days newer and the legacy writer has
-- added 22 rows since. An excess is expected, a shortfall would not be.)
--
-- Why it is needed: V12's rc_recharge points a composite foreign key at
-- (TXN_ID, SEQ_NO), while the entity RcTxnDetail maps @Id to SEQ_NO alone
-- (§8.5) — the primary key is (TXN_ID, SEQ_NO) and the entity disagrees with
-- it. This unique key makes SEQ_NO genuinely a key in its own right, so the
-- entity's mapping is sound rather than accidentally true.
--
-- The pre-existing non-unique index named SEQ_NO is left in place: it is what
-- satisfies InnoDB's requirement that an AUTO_INCREMENT column lead some
-- index, and dropping it is not in this slice's scope.
SET @idx_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rc_txn_details'
      AND INDEX_NAME = 'uk_txn_details_seq'
);
SET @sql = IF(@idx_exists = 0,
    'ALTER TABLE rc_txn_details ADD UNIQUE KEY uk_txn_details_seq (SEQ_NO)',
    'SELECT ''uk_txn_details_seq exists'' AS Info');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT 'V11: complete.' AS Status;
