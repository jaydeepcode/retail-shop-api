-- ============================================================================
-- V24: Float Valuation Tables
-- ============================================================================
-- Spec: design-ledger.md §3.2, and §8.1's V23 row.
--
-- ⚠ LIKE V23, THIS FILE CREATES A TRIGGER, so it needs the same privilege:
-- log_bin_trust_function_creators = 1, or SUPER on the migrating account.
-- V23's header has the measurements and the remedy.
--
-- SAFE ALONGSIDE THE LEGACY WRITER: new tables only, both empty.
--
-- WHY FACE VALUE IS NOT A GENERAL-LEDGER ACCOUNT (§3.1, L6). The tempting design
-- is two GL accounts per wallet — one at cost, one as a face-value memo — and it
-- is wrong for one decisive reason: if face value were a GL asset, a bonus topup
-- would recognise income BEFORE the sale. Pay Rs 2,000 for Rs 2,060 of face
-- value and the balance sheet shows a Rs 2,060 asset against a Rs 2,000 cash
-- outflow, with Rs 60 having to land somewhere — and the only place it can land
-- is income. FRD §7.2 says the opposite: "cost basis is fixed at topup time;
-- commission is realized proportionally as that batch of float is spent."
--
-- So the GL carries COST BASIS, which is the real asset, and FACE VALUE lives
-- here — a quantity of operator credit, denominated in rupees but not a money
-- claim of the shop's. These two tables are also the reconciliation source
-- against the operator portal.
--
-- TWO IDENTITIES the V27 gate and the daily reconciliation both rest on:
--     SUM(COST_DELTA) for account X  ==  acc_account_balance.BALANCE for X
--     SUM(FACE_DELTA) for account X  ==  the operator portal's balance
-- The first guarantees the out-of-GL face table has not drifted from the GL. The
-- second is FRD §7.2's reconciliation.
--
-- ⚠ ALL OF THIS IS DORMANT AT TODAY'S CONFIGURATION, AND THAT IS NOT A REASON TO
-- SIMPLIFY IT. All four of the shop's wallets are FRD §7.2 pattern 2 (log §2.4):
-- pay Rs X, receive Rs X, so cost equals face and the weighted-average ratio is
-- exactly 1.0. round(face x 1.0, 2) = face, so §3.4's last-slice snap and
-- periodic ADJUST sweep have nothing to correct. §3.5 is explicit that this
-- machinery is "dormant, not dead": it costs nothing while unreachable and
-- becomes necessary the day a bonus-on-topup wallet is added. It must not be
-- presented as day-one work, and it must not be deleted either.
--
-- ⚠ ONE DEFECT IN THE DESIGN, FIXED HERE. §3.2's DDL declares
--     CONSTRAINT fk_fmov_rate FOREIGN KEY (APPLIED_RATE_ID)
--         REFERENCES acc_commission_rate (RATE_ID)
-- on this table, but acc_commission_rate is created by the NEXT migration
-- (§8.1's V24 row, V25 here). As written the FK references a table that does not
-- exist yet and the migration fails. The column is declared here and the
-- constraint is added by V25 immediately after it creates the parent, so the FK
-- exists by the end of V25 and V27's gate asserts it by name. The alternative —
-- swapping the two migrations — was rejected because it would deviate from the
-- documented ordering a second time for no further benefit.
-- ============================================================================

SELECT 'V24: creating the float valuation tables...' AS Status;

-- ============================================================================
-- 1. acc_float_batch — one row per top-up (§3.2)
-- ============================================================================
-- A batch records what a specific purchase of float cost and what face value it
-- bought. At ratio 1.0 the two are equal; the columns are separate because the
-- ratio is a property of the deal, not of the design.
CREATE TABLE IF NOT EXISTS acc_float_batch (
    BATCH_ID         BIGINT        NOT NULL AUTO_INCREMENT,
    ACCOUNT_CODE     VARCHAR(30)   NOT NULL,
    -- The PURCHASE voucher that funded it. UNIQUE below: one voucher funds at
    -- most one batch, so a double-posted top-up fails at the database.
    TOPUP_VOUCHER_ID BIGINT        NOT NULL,
    FACE_ADDED       DECIMAL(38,2) NOT NULL,
    COST_PAID        DECIMAL(38,2) NOT NULL,
    ACQUIRED_ON      DATE          NOT NULL,
    -- The operator or portal top-up reference, so a batch can be matched against
    -- the vendor's own record.
    EXTERNAL_REF     VARCHAR(64)   NULL,
    NOTE             VARCHAR(255)  NULL,
    CRE_BY           INT           NULL,
    CRE_DTTM         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (BATCH_ID),
    CONSTRAINT fk_fbatch_account FOREIGN KEY (ACCOUNT_CODE)     REFERENCES acc_account (ACCOUNT_CODE),
    CONSTRAINT fk_fbatch_voucher FOREIGN KEY (TOPUP_VOUCHER_ID) REFERENCES acc_voucher (VOUCHER_ID),
    CONSTRAINT fk_fbatch_creby   FOREIGN KEY (CRE_BY)           REFERENCES rc_user (id) ON DELETE SET NULL,
    CONSTRAINT ck_fbatch_pos     CHECK (FACE_ADDED > 0 AND COST_PAID > 0),
    UNIQUE KEY uk_fbatch_voucher (TOPUP_VOUCHER_ID),
    KEY idx_fbatch_account_date  (ACCOUNT_CODE, ACQUIRED_ON)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================================
-- 2. acc_float_movement — the append-only signed-delta log (§3.2)
-- ============================================================================
-- SIGNED DELTAS ARE THE WHOLE DESIGN. Because every event appends a row and both
-- money columns are signed, this single expression is automatically the running
-- weighted average after any number of top-ups at any number of prices:
--
--     SELECT SUM(COST_DELTA) / NULLIF(SUM(FACE_DELTA), 0) FROM acc_float_movement
--      WHERE ACCOUNT_CODE = ?   FOR UPDATE
--
-- No separate running field to keep in step, which is why §3.4 can say WA is
-- derived and never stored.
CREATE TABLE IF NOT EXISTS acc_float_movement (
    MOVEMENT_ID    BIGINT        NOT NULL AUTO_INCREMENT,
    ACCOUNT_CODE   VARCHAR(30)   NOT NULL,
    MOVEMENT_TYPE  VARCHAR(12)   NOT NULL,
    -- Signed: +2060 topup, -199.00 sale, +199.00 reversal.
    FACE_DELTA     DECIMAL(38,2) NOT NULL,
    -- Signed: +2000 topup, -193.20 sale.
    COST_DELTA     DECIMAL(38,2) NOT NULL,
    -- Set on TOPUP only; NULL on weighted-average consumption, because a sale
    -- does not draw from an identifiable batch.
    BATCH_ID       BIGINT        NULL,
    VOUCHER_ID     BIGINT        NOT NULL,
    RECHARGE_ID    INT           NULL,
    -- APPLIED_WA and APPLIED_RATE_ID are not redundancy: they record the inputs
    -- that WERE used, so a historical recharge's cost can be explained even
    -- though WA and the rate table have moved on since. Same principle as
    -- freezing the commission amount on the voucher line (§4, L8).
    APPLIED_WA     DECIMAL(18,8) NULL,
    -- FK added by V25, once acc_commission_rate exists. See the header.
    APPLIED_RATE_ID BIGINT       NULL,
    MOVED_AT       DATETIME      NOT NULL,
    NOTE           VARCHAR(255)  NULL,
    CRE_DTTM       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (MOVEMENT_ID),
    CONSTRAINT fk_fmov_account  FOREIGN KEY (ACCOUNT_CODE)  REFERENCES acc_account (ACCOUNT_CODE),
    CONSTRAINT fk_fmov_type     FOREIGN KEY (MOVEMENT_TYPE) REFERENCES ref_float_movement_type (CODE),
    CONSTRAINT fk_fmov_batch    FOREIGN KEY (BATCH_ID)      REFERENCES acc_float_batch (BATCH_ID),
    CONSTRAINT fk_fmov_voucher  FOREIGN KEY (VOUCHER_ID)    REFERENCES acc_voucher (VOUCHER_ID),
    -- INT both sides; rc_recharge is created by the recharge module's V12.
    CONSTRAINT fk_fmov_recharge FOREIGN KEY (RECHARGE_ID)   REFERENCES rc_recharge (RECHARGE_ID),
    CONSTRAINT ck_fmov_topup    CHECK (MOVEMENT_TYPE <> 'TOPUP' OR BATCH_ID IS NOT NULL),
    CONSTRAINT ck_fmov_nonzero  CHECK (FACE_DELTA <> 0 OR COST_DELTA <> 0),
    KEY idx_fmov_account_time (ACCOUNT_CODE, MOVED_AT),
    KEY idx_fmov_voucher      (VOUCHER_ID),
    KEY idx_fmov_recharge     (RECHARGE_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================================
-- 3. The append-only trigger (§3.2)
-- ============================================================================
-- "acc_float_movement gets the same append-only treatment as acc_voucher_line"
-- (§3.2). It is a money-adjacent record and must not be editable: the running
-- weighted average is a SUM over these rows, so a mutable row would silently
-- restate the cost basis of every sale after it.
--
-- Deletion is NOT blocked here, deliberately. §3.2 allows it "only alongside its
-- voucher", and that rule is already enforced one level up: fk_fmov_voucher has
-- no ON DELETE action, so a movement cannot outlive its voucher, and V23's
-- triggers 4 and 5 decide whether that voucher may be deleted at all.
DROP TRIGGER IF EXISTS trg_acc_float_movement_bu;

DELIMITER $$

CREATE TRIGGER trg_acc_float_movement_bu BEFORE UPDATE ON acc_float_movement
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'acc_float_movement is append-only';
END$$

DELIMITER ;

SELECT 'V24: complete.' AS Status;
