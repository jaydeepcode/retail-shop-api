-- ============================================================================
-- V22: Voucher Tables
-- ============================================================================
-- Spec: design-ledger.md §2.3 and §2.5, and §8.1's V21 row.
--
-- Three tables, ALL EMPTY. acc_voucher and acc_voucher_line stay empty until the
-- opening journal is posted on go-live day (log §12), and the absence of
-- rc_setting['ledger.opening.date'] is how the system knows that has not
-- happened. V27's gate asserts both tables are empty.
--
-- SAFE ALONGSIDE THE LEGACY WRITER: new tables only.
--
-- acc_reconciliation is created HERE rather than later because V23's trigger 4
-- queries it. A trigger referencing a table that does not exist yet would create
-- fine and then fail at its first DELETE, which is the worst place to find out.
--
-- THE SEALING MODEL, because the column names do not explain themselves.
-- MySQL has no deferred constraints and no statement-level CHECK, so "the legs
-- of a voucher sum to zero" cannot be a CHECK: a row-level constraint would
-- reject the first leg of a two-leg voucher. What works is a trigger on the
-- PARENT, which can run an aggregate over the children. So a post is:
--
--     1. INSERT acc_voucher with SEALED_AT NULL
--     2. INSERT acc_voucher_line, n of them
--     3. UPDATE acc_voucher SET SEALED_AT = now   <- V23 trigger 1 checks here
--
-- Step 3 turns a cross-row invariant into one checkable moment. Everything the
-- reports read filters on SEALED_AT IS NOT NULL, so a half-built voucher is
-- invisible rather than wrong.
-- ============================================================================

SELECT 'V22: creating the voucher tables...' AS Status;

-- ============================================================================
-- 1. acc_voucher (§2.3)
-- ============================================================================
-- BIGINT ids on both tables. acc_voucher_line is the only table here that grows
-- continuously (~3 lines per event, ~15-20k lines/year), so INT would last
-- 100,000 years — this is not about headroom. It is about never having to think
-- about the FK type again, for 4 bytes a row. Every FK to these tables must also
-- declare BIGINT; this repo has already been bitten by a mediumint/int mismatch
-- (design-domain.md §8.6).
CREATE TABLE IF NOT EXISTS acc_voucher (
    VOUCHER_ID       BIGINT       NOT NULL AUTO_INCREMENT,
    VOUCHER_TYPE     VARCHAR(10)  NOT NULL,
    -- The ACCOUNTING date: reports, reconciliation and §8.3 dating all use this.
    VOUCHER_DATE     DATE         NOT NULL,
    -- When the event happened. Orders entries within a day, which VOUCHER_DATE
    -- alone cannot do.
    VOUCHER_DTTM     DATETIME     NOT NULL,
    NARRATION        VARCHAR(255) NULL,
    SOURCE_TYPE      VARCHAR(20)  NOT NULL,
    -- 'rc_txn_header#73460', 'wt_purchase_details#6182', 'rc_recharge#12'.
    -- A varchar and not a typed FK on purpose: rc_txn_header.TXN_ID is mediumint
    -- while wt_purchase_details.id is int, so one typed column cannot serve both,
    -- and six mostly-NULL typed FK columns is worse than one varchar. Cost: no
    -- referential integrity voucher -> operational row, compensated by the
    -- verification queries in the V27/V29 gates and the drift check (§6.4).
    SOURCE_REF       VARCHAR(64)  NULL,
    -- Nullable-unique: MySQL allows many NULLs in a unique index, so manual
    -- vouchers (which have no natural key) leave it NULL while every automatic
    -- posting sets it. This is the whole idempotency mechanism (§5.4).
    IDEMPOTENCY_KEY  VARCHAR(120) NULL,
    -- A JOURNAL that corrects an earlier voucher (§8.3). The original stays
    -- byte-identical; this is what links them.
    REVERSES_VOUCHER_ID BIGINT    NULL,
    -- Set as the last statement of a post. The balance is asserted here, and
    -- once set the row is immutable (V23 trigger 1).
    SEALED_AT        DATETIME     NULL,
    CRE_BY           INT          NULL,
    CRE_DTTM         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (VOUCHER_ID),
    CONSTRAINT fk_vch_type    FOREIGN KEY (VOUCHER_TYPE) REFERENCES ref_voucher_type (CODE),
    CONSTRAINT fk_vch_source  FOREIGN KEY (SOURCE_TYPE)  REFERENCES ref_voucher_source (CODE),
    CONSTRAINT fk_vch_reverse FOREIGN KEY (REVERSES_VOUCHER_ID) REFERENCES acc_voucher (VOUCHER_ID),
    CONSTRAINT fk_vch_creby   FOREIGN KEY (CRE_BY) REFERENCES rc_user (id) ON DELETE SET NULL,
    -- L10: a duplicate post fails at the DB, on every code path including
    -- hand-SQL. Application-side "have I posted this already?" would not.
    UNIQUE KEY uk_vch_idem (IDEMPOTENCY_KEY),
    KEY idx_vch_date      (VOUCHER_DATE, VOUCHER_ID),
    KEY idx_vch_type_date (VOUCHER_TYPE, VOUCHER_DATE),
    KEY idx_vch_source    (SOURCE_TYPE, SOURCE_REF),
    KEY idx_vch_unsealed  (SEALED_AT)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================================
-- 2. acc_voucher_line (§2.3)
-- ============================================================================
-- TWO MONEY COLUMNS, NOT ONE SIGNED COLUMN. SUM(DR_AMOUNT) = SUM(CR_AMOUNT)
-- reads as the accounting invariant it is, and a hand-written report shows
-- debits and credits in the columns a bookkeeper expects with no
-- per-account-type sign rule. A single signed AMOUNT is arithmetically simpler
-- (SUM = 0) but then every human reading the table has to know that a positive
-- number on an income account means the opposite of a positive number on a cash
-- account.
--
-- DECIMAL(38,2) matches the existing money columns exactly. The Java side is
-- BigDecimal, consistent with the zero double/float in this codebase.
CREATE TABLE IF NOT EXISTS acc_voucher_line (
    LINE_ID        BIGINT        NOT NULL AUTO_INCREMENT,
    VOUCHER_ID     BIGINT        NOT NULL,
    LINE_NO        SMALLINT      NOT NULL,
    ACCOUNT_CODE   VARCHAR(30)   NOT NULL,
    DR_AMOUNT      DECIMAL(38,2) NOT NULL DEFAULT 0.00,
    CR_AMOUNT      DECIMAL(38,2) NOT NULL DEFAULT 0.00,
    -- Subledger dimension for AR_* / ADV_* legs. The customer statement query
    -- (§2.6) drives from this.
    PARTY_CUST_ID  INT           NULL,
    -- CASH | UPI | CRDT on money-in legs (FRD §8.6). FKs the recharge module's
    -- ref_payment_line_type rather than defining a second vocabulary for the
    -- same concept.
    PAYMENT_METHOD VARCHAR(10)   NULL,
    LINE_NARRATION VARCHAR(255)  NULL,
    PRIMARY KEY (LINE_ID),
    UNIQUE KEY uk_line_voucher_no (VOUCHER_ID, LINE_NO),
    CONSTRAINT fk_line_voucher FOREIGN KEY (VOUCHER_ID)     REFERENCES acc_voucher (VOUCHER_ID),
    CONSTRAINT fk_line_account FOREIGN KEY (ACCOUNT_CODE)   REFERENCES acc_account (ACCOUNT_CODE),
    CONSTRAINT fk_line_party   FOREIGN KEY (PARTY_CUST_ID)  REFERENCES rs_cust_dtls (CUST_ID),
    CONSTRAINT fk_line_paymeth FOREIGN KEY (PAYMENT_METHOD) REFERENCES ref_payment_line_type (CODE),
    -- The three row-level CHECKs are what still guarantee every leg is
    -- well-formed even if the V23 triggers were ever dropped (§2.4's fallback).
    CONSTRAINT ck_line_nonneg  CHECK (DR_AMOUNT >= 0 AND CR_AMOUNT >= 0),
    CONSTRAINT ck_line_oneside CHECK (DR_AMOUNT = 0 OR CR_AMOUNT = 0),
    CONSTRAINT ck_line_nonzero CHECK (DR_AMOUNT + CR_AMOUNT > 0),
    -- Makes the balance view an index-only aggregate (§2.6).
    KEY idx_line_account (ACCOUNT_CODE, VOUCHER_ID),
    KEY idx_line_party   (PARTY_CUST_ID, ACCOUNT_CODE),
    KEY idx_line_paymeth (PAYMENT_METHOD, VOUCHER_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================================
-- 3. acc_reconciliation (§2.5)
-- ============================================================================
-- Covers all three reconciliation points with one table: daily cash (FRD §8.1),
-- UPI settlement (§8.6), operator float versus portal (§7.2).
--
-- A POOL COUNT WRITES ONE ROW PER MEMBER ACCOUNT, NOT ONE PER POOL (log §9.1).
-- This is not a style choice. V23's trigger 4 locks an account against voucher
-- deletion only if an acc_reconciliation row exists FOR THAT ACCOUNT, so a
-- single pool-level row would leave CASH_TICKET permanently editable after its
-- drawer had been counted and closed — exactly the regime FRD §8.3 exists to
-- prevent. The apportionment: non-primary members carry their own derived
-- balance in both columns (variance 0.00 by construction), and the
-- IS_POOL_PRIMARY member carries the physical count minus the other members'
-- balances, so the pool's whole variance lands there without a rule in code.
--
-- IS LEDGER_AMOUNT A STORED BALANCE THAT FRD §8.4 FORBIDS? No. §8.4 forbids a
-- MAINTAINED balance that reports read instead of the vouchers. This is a
-- one-time observation: the derived balance as it stood BEFORE the variance
-- journal was posted. Once that journal exists the pre-adjustment balance is no
-- longer recoverable from the vouchers, so it has to be recorded or the evidence
-- of what was wrong is destroyed.
CREATE TABLE IF NOT EXISTS acc_reconciliation (
    RECON_ID        BIGINT        NOT NULL AUTO_INCREMENT,
    ACCOUNT_CODE    VARCHAR(30)   NOT NULL,
    RECON_DATE      DATE          NOT NULL,
    -- The independent source: a cash count, a bank statement, a portal balance.
    COUNTED_AMOUNT  DECIMAL(38,2) NOT NULL,
    LEDGER_AMOUNT   DECIMAL(38,2) NOT NULL,
    -- Derived by the engine, never independently writable.
    VARIANCE_AMOUNT DECIMAL(38,2) AS (COUNTED_AMOUNT - LEDGER_AMOUNT) STORED,
    -- Float accounts only: the portal's face balance and SUM(FACE_DELTA) (§3).
    FACE_COUNTED    DECIMAL(38,2) NULL,
    FACE_LEDGER     DECIMAL(38,2) NULL,
    -- FRD §8.1's "a variance with a note".
    NOTE            VARCHAR(255)  NULL,
    -- The voucher that booked the variance. Deliberately not restricted to a
    -- JOURNAL: log §9.1 part 2 books a variance with a KNOWN cause as an
    -- ordinary PAYMENT for the real expense, because booking it to "Cash Short"
    -- throws away the one fact worth keeping.
    VARIANCE_VOUCHER_ID BIGINT    NULL,
    CLOSED_AT       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CLOSED_BY       INT           NULL,
    PRIMARY KEY (RECON_ID),
    UNIQUE KEY uk_recon_account_date (ACCOUNT_CODE, RECON_DATE),
    CONSTRAINT fk_recon_account  FOREIGN KEY (ACCOUNT_CODE) REFERENCES acc_account (ACCOUNT_CODE),
    CONSTRAINT fk_recon_voucher  FOREIGN KEY (VARIANCE_VOUCHER_ID) REFERENCES acc_voucher (VOUCHER_ID),
    CONSTRAINT fk_recon_closedby FOREIGN KEY (CLOSED_BY) REFERENCES rc_user (id) ON DELETE SET NULL,
    KEY idx_recon_date (RECON_DATE, ACCOUNT_CODE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SELECT 'V22: complete.' AS Status;
