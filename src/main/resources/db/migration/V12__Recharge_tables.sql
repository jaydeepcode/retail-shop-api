-- ============================================================================
-- V12: Recharge Tables
-- ============================================================================
-- Spec: design-domain.md §6.3 (the V12 row), §4.3, §4.4, §5.4, §7.2.
--
-- Creates the three tables the recharge module is built on, ALL EMPTY:
--   rc_txn_payment          — how a basket was paid for (CASH / UPI / CRDT)
--   rc_recharge             — the aggregate root: one row per recharge
--   rc_recharge_status_hist — the status audit trail
--
-- SAFE ALONGSIDE THE LEGACY WRITER: three new tables, no existing row touched.
-- Populating them from history is V13's and V15's job, on cutover day.
--
-- ⚠ MEDIUMINT, not INT, wherever a column points at rc_txn_header or
-- rc_txn_details. MySQL rejects a foreign key whose child column type does not
-- match the parent's, and §4.4 names this as the most likely thing to make a
-- first attempt fail. The legacy keys really are mediumint — verified.
-- ============================================================================

SELECT 'V12: creating the recharge tables...' AS Status;

-- ============================================================================
-- 1. rc_txn_payment (§4.3)
-- ============================================================================
-- One row per tender on a basket. AMOUNT is always POSITIVE and the lines SUM
-- TO txn_total_amt — note the sign flip against history, where the 2,844 CRDT
-- rows in rc_txn_details are all negative and cancel the total out rather than
-- composing it.
--
-- EXTENDED_BY and NOTE live here, not only on rc_credit_req, because
-- requirement 7's credit-line facts are facts about THIS settlement. They are
-- copied onto rc_credit_req as well so the credit book stays self-contained.
CREATE TABLE IF NOT EXISTS rc_txn_payment (
    TXN_ID       MEDIUMINT     NOT NULL,
    SEQ_NO       SMALLINT      NOT NULL,
    LINE_TYPE    VARCHAR(10)   NOT NULL,
    AMOUNT       DECIMAL(38,2) NOT NULL,
    REFERENCE_NO VARCHAR(255)  NULL,
    CRE_REQ_ID   INT           NULL,
    EXTENDED_BY  INT           NULL,
    NOTE         VARCHAR(255)  NULL,
    CRE_DTTM     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Mirrors rc_txn_details' (TXN_ID, SEQ_NO) on purpose: the item lines and
    -- the payment lines of a basket then read the same way.
    PRIMARY KEY (TXN_ID, SEQ_NO),
    KEY idx_txnpay_type (LINE_TYPE, TXN_ID),
    KEY idx_txnpay_credit (CRE_REQ_ID),
    CONSTRAINT fk_txnpay_header FOREIGN KEY (TXN_ID)
        REFERENCES rc_txn_header (TXN_ID),
    CONSTRAINT fk_txnpay_type FOREIGN KEY (LINE_TYPE)
        REFERENCES ref_payment_line_type (CODE),
    CONSTRAINT fk_txnpay_credit FOREIGN KEY (CRE_REQ_ID)
        REFERENCES rc_credit_req (CRE_REQ_ID),
    CONSTRAINT fk_txnpay_extby FOREIGN KEY (EXTENDED_BY)
        REFERENCES rc_user (id) ON DELETE SET NULL,
    CONSTRAINT ck_txnpay_amount CHECK (AMOUNT > 0),
    -- A credit line without the credit-book row it wrote is meaningless, and
    -- unlike the "credit requires a named customer" rule this one lives inside
    -- a single row, so a CHECK can hold it.
    CONSTRAINT ck_txnpay_credit CHECK (LINE_TYPE <> 'CRDT' OR CRE_REQ_ID IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================================
-- 2. rc_recharge (§4.4) — the aggregate root
-- ============================================================================
-- TARGET_NUMBER has NO foreign key on purpose: it is a mobile number or a TV
-- connection number typed at the counter, and most of them correspond to no
-- row anywhere. DISHTV_NO is the optional, real relation — set only when the
-- number is a known connection.
--
-- ck_rech_amount is safe for V15's backfill: exactly ONE historical recharge
-- line has amount <= 0, and V15 excludes and logs it (risk R4).
CREATE TABLE IF NOT EXISTS rc_recharge (
    RECHARGE_ID        INT           NOT NULL AUTO_INCREMENT,
    RECHARGE_KIND      VARCHAR(10)   NOT NULL,
    -- VARCHAR(20), not §4.4's VARCHAR(10): it foreign-keys rc_operator, whose
    -- OPERATOR_CODE had to be widened to hold 'TVOPR_UNKNOWN' (13 chars). See
    -- the correction note in V10. A mismatch here is errno 150.
    OPERATOR_CODE      VARCHAR(20)   NOT NULL,
    ROUTE_CODE         VARCHAR(20)   NOT NULL,
    TARGET_NUMBER      VARCHAR(20)   NOT NULL,
    DISHTV_NO          VARCHAR(12)   NULL,
    AMOUNT             DECIMAL(38,2) NOT NULL,
    RECHARGE_DTTM      DATETIME      NOT NULL,
    STATUS_CODE        VARCHAR(20)   NOT NULL,
    TXN_ID             MEDIUMINT     NOT NULL,
    SEQ_NO             MEDIUMINT     NOT NULL,
    FAILED_RECHARGE_ID INT           NULL,
    EXTERNAL_REF       VARCHAR(64)   NULL,
    FAILURE_REASON     VARCHAR(255)  NULL,
    CRE_BY             INT           NULL,
    CRE_DTTM           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    STATUS_CHANGED_AT  DATETIME      NULL,
    STATUS_CHANGED_BY  INT           NULL,
    PRIMARY KEY (RECHARGE_ID),
    -- §7.2. The two date/status composites are not redundant: the history
    -- screen sorts by date within a status-agnostic range, while the open-items
    -- query needs leading equality on status to avoid a scan.
    KEY idx_rech_dttm_status (RECHARGE_DTTM, STATUS_CODE),
    KEY idx_rech_status_dttm (STATUS_CODE, RECHARGE_DTTM),
    KEY idx_rech_number (TARGET_NUMBER),
    KEY idx_rech_txn (TXN_ID),
    -- Named explicitly so the self-FK and the "what retried this?" query share
    -- one documented index rather than an auto-generated one.
    KEY idx_rech_retry (FAILED_RECHARGE_ID),
    -- Also what the composite FK below uses, and the §2.2(3) check that at most
    -- one non-terminal attempt exists per item line.
    KEY idx_rech_line (TXN_ID, SEQ_NO),
    CONSTRAINT fk_rech_kind FOREIGN KEY (RECHARGE_KIND)
        REFERENCES ref_recharge_kind (CODE),
    CONSTRAINT fk_rech_operator FOREIGN KEY (OPERATOR_CODE)
        REFERENCES rc_operator (OPERATOR_CODE),
    CONSTRAINT fk_rech_route FOREIGN KEY (ROUTE_CODE)
        REFERENCES ref_recharge_route (CODE),
    CONSTRAINT fk_rech_status FOREIGN KEY (STATUS_CODE)
        REFERENCES ref_recharge_status (CODE),
    CONSTRAINT fk_rech_line FOREIGN KEY (TXN_ID, SEQ_NO)
        REFERENCES rc_txn_details (TXN_ID, SEQ_NO),
    CONSTRAINT fk_rech_dishtv FOREIGN KEY (DISHTV_NO)
        REFERENCES rc_dishtv_dtls (DISHTV_NO),
    CONSTRAINT fk_rech_retry FOREIGN KEY (FAILED_RECHARGE_ID)
        REFERENCES rc_recharge (RECHARGE_ID),
    CONSTRAINT fk_rech_creby FOREIGN KEY (CRE_BY)
        REFERENCES rc_user (id) ON DELETE SET NULL,
    CONSTRAINT ck_rech_amount CHECK (AMOUNT > 0),
    -- A TV connection number on a mobile recharge is a data error, not a
    -- variation.
    CONSTRAINT ck_rech_tv CHECK (DISHTV_NO IS NULL OR RECHARGE_KIND = 'TV')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================================
-- 3. rc_recharge_status_hist (§5.4)
-- ============================================================================
-- Appended by RechargeService.transition(), inside the same transaction as the
-- status write. §5.4 calls it recommended rather than strictly required by v1:
-- it costs one small table and answers "when did it fail", which the client
-- will ask within a week.
--
-- It is NOT a substitute for the audit package, which is about user actions
-- rather than domain state.
--
-- FROM_STATUS is nullable for the creating transition, which has no prior
-- state. REASON is nullable here because the database cannot tell which
-- transitions mandate one — COMPLETED → VOIDED and COMPLETED → FAILED both
-- require a reason (log §7.1), and the state machine enforces that.
CREATE TABLE IF NOT EXISTS rc_recharge_status_hist (
    HIST_ID     INT          NOT NULL AUTO_INCREMENT,
    RECHARGE_ID INT          NOT NULL,
    FROM_STATUS VARCHAR(20)  NULL,
    TO_STATUS   VARCHAR(20)  NOT NULL,
    CHANGED_AT  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHANGED_BY  INT          NULL,
    REASON      VARCHAR(255) NULL,
    PRIMARY KEY (HIST_ID),
    KEY idx_rechhist_recharge (RECHARGE_ID, CHANGED_AT),
    CONSTRAINT fk_rechhist_recharge FOREIGN KEY (RECHARGE_ID)
        REFERENCES rc_recharge (RECHARGE_ID),
    CONSTRAINT fk_rechhist_from FOREIGN KEY (FROM_STATUS)
        REFERENCES ref_recharge_status (CODE),
    CONSTRAINT fk_rechhist_to FOREIGN KEY (TO_STATUS)
        REFERENCES ref_recharge_status (CODE),
    CONSTRAINT fk_rechhist_changedby FOREIGN KEY (CHANGED_BY)
        REFERENCES rc_user (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SELECT 'V12: complete.' AS Status;
