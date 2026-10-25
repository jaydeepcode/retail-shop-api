-- ============================================================================
-- V31: Ticket booking — ref_booking_mode and tkt_booking
-- ============================================================================
-- Spec: design-ticket-booking.md §2 and §6.1.
--
-- Empty tables only. No money moves, and nothing historical is touched: pre-D
-- RAIL/RILBS lines stay in rc_txn_details and keep being read by the existing
-- screens (T7, design-ledger.md §7.1). The backfill is S6's and is not this
-- slice's to guess at.
--
-- ⚠ NO ref_voucher_source INSERT, AND THAT IS A CORRECTION TO §6.1.
-- The spec's V31 row says to "Add TICKET_BOOKING to ref_voucher_source". V21
-- ALREADY DID, at V21__Ledger_reference_and_chart_of_accounts.sql:185 —
-- ('TICKET_BOOKING', 'Rail or bus ticket booking', TRUE). Re-adding it is a
-- duplicate-key failure on a migration that otherwise works, which would leave
-- the schema half-applied. Every account the posting rules need is seeded there
-- too: CASH_TICKET (:285), BANK_JANATA (:287), BANK_SELF (:288), BANK_WIFE
-- (:289), BANK_FATHER (:290), AR_TICKET (:294), INC_TKT_COMM (:303). Nothing in
-- the chart of accounts is owed by this slice.
--
-- ⚠ APPEND-ONLY FROM THE MOMENT THIS IS APPLIED ANYWHERE, including the
-- throwaway database on 3307. Editing an applied migration in place is what
-- produced V30: it changes the checksum so `flyway validate` fails on every
-- database carrying the old text and the application stops starting at all, AND
-- it does not apply, because Flyway never re-runs an applied migration — so the
-- repository and the database disagree while reporting the same version. A
-- change of mind here becomes V33. See db/migration/README.md.
-- ============================================================================

SELECT 'V31: creating the ticket booking tables...' AS Status;

-- ============================================================================
-- 1. ref_booking_mode — the two modes, as data rather than as an enum column
-- ============================================================================
-- §2: this follows the ref_* convention used eight times already
-- (design-domain.md §4.8), so the screen's picker is a query and adding a mode
-- is a data change rather than a release.
--
-- Rail and bus are ONE BUSINESS with a BOOKING_MODE dimension (T3, log §10.7):
-- identical economics, one income account, ₹60 per person either way. A second
-- income account for bus was explicitly scoped out.
CREATE TABLE IF NOT EXISTS ref_booking_mode (
    CODE         VARCHAR(10)  NOT NULL,
    DISPLAY_NAME VARCHAR(40)  NOT NULL,
    SORT_ORDER   INT          NOT NULL DEFAULT 0,
    IS_ACTIVE    BOOLEAN      NOT NULL DEFAULT TRUE,
    PRIMARY KEY (CODE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ⚠ The collation is spelled out rather than left to CHARSET=utf8mb4 alone. A
-- bare charset can resolve to utf8mb4_general_ci, and BOOKING_MODE would then
-- not foreign-key against this table (design-ledger.md §8.2 — V27 asserts the
-- same thing for the ledger because this has bitten before).

INSERT INTO ref_booking_mode (CODE, DISPLAY_NAME, SORT_ORDER, IS_ACTIVE)
SELECT src.* FROM (
  SELECT 'RAIL' AS CODE, 'Railway' AS DISPLAY_NAME, 10 AS SORT_ORDER, TRUE AS IS_ACTIVE
  UNION ALL SELECT 'BUS', 'Bus', 20, TRUE
) src
WHERE NOT EXISTS (SELECT 1 FROM ref_booking_mode WHERE CODE = src.CODE);

-- ============================================================================
-- 2. tkt_booking — one row per booking
-- ============================================================================
-- FARE_AMOUNT IS NULL is how flow 2 is expressed: the customer paid the platform
-- themselves and the shop never touched the fare, so there is nothing to record
-- and no funding account to name. The flow is READ from the row (T2), never
-- stored as a flag that could contradict it.
CREATE TABLE IF NOT EXISTS tkt_booking (
    BOOKING_ID           BIGINT        NOT NULL AUTO_INCREMENT,
    BOOKING_DTTM         DATETIME      NOT NULL,
    BOOKING_MODE         VARCHAR(10)   NOT NULL,
    PASSENGER_COUNT      SMALLINT      NOT NULL,
    FARE_AMOUNT          DECIMAL(38,2) NULL,
    COMMISSION_AMOUNT    DECIMAL(38,2) NOT NULL,
    FUNDING_ACCOUNT_CODE VARCHAR(30)   NULL,
    CUST_ID              INT           NULL,
    NOTE                 VARCHAR(255)  NULL,
    CRE_BY               INT           NULL,
    CRE_DTTM             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UPD_DTTM             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (BOOKING_ID),

    CONSTRAINT fk_tkt_mode    FOREIGN KEY (BOOKING_MODE)         REFERENCES ref_booking_mode (CODE),
    CONSTRAINT fk_tkt_funding FOREIGN KEY (FUNDING_ACCOUNT_CODE) REFERENCES acc_account (ACCOUNT_CODE),
    CONSTRAINT fk_tkt_cust    FOREIGN KEY (CUST_ID)              REFERENCES rs_cust_dtls (CUST_ID),
    CONSTRAINT fk_tkt_creby   FOREIGN KEY (CRE_BY)               REFERENCES rc_user (id) ON DELETE SET NULL,

    CONSTRAINT ck_tkt_pax     CHECK (PASSENGER_COUNT > 0),
    CONSTRAINT ck_tkt_comm    CHECK (COMMISSION_AMOUNT >= 0),
    CONSTRAINT ck_tkt_fare    CHECK (FARE_AMOUNT IS NULL OR FARE_AMOUNT > 0),

    -- ⚠ THE CONSTRAINT THAT MAKES THE WHOLE DESIGN SAFE.
    -- A flow-2 booking cannot carry a funding account and a flow-1 booking
    -- cannot omit one, so "which flow was this?" is answerable from the row and
    -- cannot be answered two different ways. It is what lets the screen offer
    -- ONE control — "customer paid directly" IS the funding picker — instead of
    -- a flow toggle that could disagree with the amounts (T2, §7).
    CONSTRAINT ck_tkt_funding CHECK ((FARE_AMOUNT IS NULL) = (FUNDING_ACCOUNT_CODE IS NULL)),

    KEY idx_tkt_date    (BOOKING_DTTM),
    KEY idx_tkt_mode    (BOOKING_MODE, BOOKING_DTTM),
    KEY idx_tkt_funding (FUNDING_ACCOUNT_CODE, BOOKING_DTTM),
    KEY idx_tkt_cust    (CUST_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- PASSENGER_COUNT is STORED and never inferred from commission ÷ 60 (T6). FY2026
-- holds ₹3,435 and ₹2,160 of commission on single baskets, which would imply
-- 57.25 and 36 passengers. The defect those rows show is an implausible
-- passenger count, not indivisibility — which is also why a "multiple of 60"
-- CHECK would be wrong: it catches ₹3,435 and waves ₹2,160 straight through.
-- ₹60 per person is today's price, not a law, so it is sent and recorded.

-- No PLATFORM, PNR or per-passenger table. All three were scoped out with the
-- owner, none has ever been recorded, and none is needed to compute margin.
-- NOTE absorbs whatever the staff want for their own lookup. Adding a typed
-- column later is additive; guessing its shape now is not (§2, §7).

SELECT 'V31: complete.' AS Status;
