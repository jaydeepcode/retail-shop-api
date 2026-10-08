-- ============================================================================
-- V25: Commission Rate Table
-- ============================================================================
-- Spec: design-ledger.md §4, §4.1, §4.2; log §8.2, §8.3, §8.4. §8.1's V24 row.
--
-- SAFE ALONGSIDE THE LEGACY WRITER: one new table, plus one FK added to V24's
-- acc_float_movement, which is new and empty.
--
-- ⚠ OPERATOR_CODE IS VARCHAR(20), NOT §4's VARCHAR(10). Same reason as V21's
-- acc_account.OPERATOR_CODE: it foreign-keys rc_operator.OPERATOR_CODE, which
-- S1's V10 widened to VARCHAR(20). A mismatch is errno 150. V10's header names
-- acc_account as the downstream consequence but NOT this table — it is a second
-- instance of the same correction, found while implementing S2.
--
-- WHY THE SEEDS ARE HERE AT ALL — this reverses the original "no rate seeds"
-- plan (log §8.2, §8.4). Every reachable (active operator, active route) pair
-- must carry an OPEN catch-all row before the counter can trade, because a
-- lookup miss now REFUSES the income post rather than defaulting to r = 0. So
-- the seeds are a correctness requirement, not client data.
--
-- AND r = 0 WAS NEVER A NEUTRAL DEFAULT, which is why that reversal happened.
-- r does not only set commission — it sets the float decrement. At r = 0 the
-- system records the portal debiting Rs 199 on a Rs 199 sale when it really
-- debited Rs 193.23: commission is understated AND SUM(FACE_DELTA) drifts from
-- the portal balance by the same amount, breaking the one independent check the
-- design has on float. At 2026 volumes, one operator's rate missing for a year
-- is roughly Rs 3,000 of understated commission plus Rs 3,000 of unexplained
-- float drift — small, silent, and precisely the leak FRD §1 exists to surface.
--
-- EFFECTIVE_FROM IS THIS MIGRATION'S OWN RUN DATE, NOT D (log §12,
-- design-ledger-api.md §5). No sale can post before the opening journal exists,
-- so an earlier effective date cannot misdate anything — and nothing then needs
-- the go-live date at build time. §4's seed table says "dated EFFECTIVE_FROM = D"
-- in one sentence and the run date in another; the run date is the resolved
-- answer.
-- ============================================================================

SELECT 'V25: creating the commission rate table...' AS Status;

-- ============================================================================
-- 1. acc_commission_rate (§4)
-- ============================================================================
-- ROUTE_CODE was added by log §8.3 before this table ever shipped, and it is not
-- cosmetic: the same operator pays materially different rates by route, IN
-- OPPOSITE DIRECTIONS ACROSS THE BOOK. Airtel earns 3.00% direct against 0.80%
-- through A1Topup, while Vi earns 2.50% direct against 3.30% through A1Topup. No
-- operator-only key can express that, and §2.4 resolves the float account from
-- operator and route together, so a rate table keyed on operator alone would
-- make the two halves of the posting rule disagree about what identifies a
-- recharge.
CREATE TABLE IF NOT EXISTS acc_commission_rate (
    RATE_ID        BIGINT        NOT NULL AUTO_INCREMENT,
    -- VARCHAR(20), not §4's VARCHAR(10) — see the header.
    OPERATOR_CODE  VARCHAR(20)   NOT NULL,
    -- '*' = any route. Available, but no seeded row uses it: seeding no
    -- wildcards means §8.2's mandatory-catch-all guarantee cannot be weakened by
    -- a wildcard silently absorbing a route whose rate was never entered.
    ROUTE_CODE     VARCHAR(20)   NOT NULL DEFAULT '*',
    PLAN_TIER      VARCHAR(30)   NOT NULL DEFAULT '*',
    -- Amount band, half-open [MIN, MAX).
    MIN_AMOUNT     DECIMAL(38,2) NOT NULL DEFAULT 0.00,
    MAX_AMOUNT     DECIMAL(38,2) NULL,
    -- 2.900000 means 2.9%.
    RATE_PCT       DECIMAL(9,6)  NOT NULL,
    EFFECTIVE_FROM DATE          NOT NULL,
    EFFECTIVE_TO   DATE          NULL,
    NOTE           VARCHAR(255)  NULL,
    SUPERSEDED_BY  BIGINT        NULL,
    CRE_BY         INT           NULL,
    CRE_DTTM       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (RATE_ID),
    CONSTRAINT fk_rate_operator FOREIGN KEY (OPERATOR_CODE) REFERENCES rc_operator (OPERATOR_CODE),
    CONSTRAINT fk_rate_route    FOREIGN KEY (ROUTE_CODE)    REFERENCES ref_recharge_route (CODE),
    CONSTRAINT fk_rate_super    FOREIGN KEY (SUPERSEDED_BY) REFERENCES acc_commission_rate (RATE_ID),
    CONSTRAINT fk_rate_creby    FOREIGN KEY (CRE_BY)        REFERENCES rc_user (id) ON DELETE SET NULL,
    CONSTRAINT ck_rate_pct      CHECK (RATE_PCT >= 0 AND RATE_PCT < 100),
    CONSTRAINT ck_rate_band     CHECK (MAX_AMOUNT IS NULL OR MAX_AMOUNT > MIN_AMOUNT),
    CONSTRAINT ck_rate_period   CHECK (EFFECTIVE_TO IS NULL OR EFFECTIVE_TO >= EFFECTIVE_FROM),
    -- Only stops EXACT duplicates. MySQL has no exclusion constraint, so
    -- overlapping rows cannot be prevented here — §4.2's ORDER BY makes the
    -- lookup deterministic even if an overlap exists, the service refuses to
    -- insert one, and V27 reports any that appear. A named limitation, not a
    -- solved problem.
    UNIQUE KEY uk_rate_key (OPERATOR_CODE, ROUTE_CODE, PLAN_TIER, MIN_AMOUNT, EFFECTIVE_FROM),
    KEY idx_rate_lookup (OPERATOR_CODE, ROUTE_CODE, EFFECTIVE_FROM, EFFECTIVE_TO)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================================
-- 2. V24's deferred foreign key
-- ============================================================================
-- acc_float_movement.APPLIED_RATE_ID was declared in V24 without its FK, because
-- §3.2's DDL references acc_commission_rate one migration before it exists. See
-- V24's header. Guarded so a re-run is a no-op (the V3:18-31 / V10:33-44 idiom).
SET @fk_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'acc_float_movement'
       AND CONSTRAINT_NAME = 'fk_fmov_rate'
       AND CONSTRAINT_TYPE = 'FOREIGN KEY'
);
SET @sql = IF(
    @fk_exists = 0,
    'ALTER TABLE acc_float_movement
        ADD CONSTRAINT fk_fmov_rate FOREIGN KEY (APPLIED_RATE_ID)
            REFERENCES acc_commission_rate (RATE_ID)',
    'SELECT ''fk_fmov_rate exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================================
-- 3. Rate immutability is a service rule, not a trigger
-- ============================================================================
-- §4.1 specifies a BEFORE UPDATE trigger refusing any change to the identifying
-- columns or the rate, refusing a second EFFECTIVE_TO, and refusing a close
-- dated in the past. Moved into the service on 2026-10-09: every one of those is
-- ACCOUNTING POLICY -- "close the row and insert a successor" -- and policy reads
-- far better as a 422 with a sentence than as SIGNAL SQLSTATE '45000'. It lives
-- in LedgerCorrectionRules.assertRateClosable.
--
-- ⚠ AND §4.1 IS EXPLICIT THAT THIS TRIGGER WAS NEVER THE REAL GUARANTEE ANYWAY.
-- It lists two mechanisms and says the second is the one that matters: "the
-- POSTED AMOUNT is the record, not the rate. Historical P&L is a SUM over
-- acc_voucher_line, whose rows are immutable. Even a successful attack on the
-- rate table cannot move a reported figure." The rate table is an INPUT LOG for
-- future postings. So losing trigger-level enforcement here costs much less than
-- it would on the voucher tables, which is why this one was an easy move.
--
-- Nothing writes this table in S2 except the seeds below; the rate-admin screen
-- is later work and is what will call the guard.
DROP TRIGGER IF EXISTS trg_acc_commission_rate_bu;

-- ============================================================================
-- 4. The rate seeds (§4, log §8.4)
-- ============================================================================
-- TWELVE ROWS, NOT §4's FOURTEEN. §4 lists IDEA and VDFN separately, with
-- identical rates on both routes (2.50% direct, 3.30% through A1Topup). V20
-- merged them into VI per log §2.4, so those four rows become two and NO RATE
-- INFORMATION IS LOST — the merge is exactly lossless on this dimension, which
-- is independent evidence that the two codes really were one wallet.
--
-- PROVENANCE IS RECORDED IN THE NOTE, because it decides what may be changed
-- silently. The A1Topup figures are read from the vendor portal's "My
-- Commission" screen and are authoritative. The three direct rates were given
-- from memory and are PROVISIONAL pending confirmation with the business
-- (log §13.4's last row — and they are self-correcting: sell one Rs 199 Airtel,
-- read the portal debit, r = 1 - debit/199).
--
-- A RATE THAT IS NOT YET KNOWN IS SEEDED AT ZERO, NEVER AT A GUESS (log §8.4).
-- BIGTV is absent from the portal list, so it gets 0.000000 and a NOTE saying
-- so. The rate is observable rather than remembered — under pattern 2 the portal
-- debits A x (1 - r), so one recharge reveals it exactly. A guessed 2.5% reads
-- as authoritative in the rate screen a year later; a zero is self-evidently a
-- placeholder.
--
-- NO BSNL/DIRECT ROW, deliberately. Log §2.4 establishes BSNL has no direct
-- connection, so selecting that combination SHOULD hit §4.2's refusal. That is
-- the correct outcome, not a gap — and it is why V27's rate-coverage assertion
-- cannot be a cross join of operators and routes. See V27's header.
--
-- Amount banding is deliberately not seeded. 90.28% of CY2026 mobile volume sits
-- in Rs 100-399 and 299/199/147/349 are 78.87% of it (log §14.10), so bands can
-- be added later as ordinary effective-dated rows without disturbing the
-- catch-all. Every row here is PLAN_TIER='*', MIN_AMOUNT=0, MAX_AMOUNT=NULL.
INSERT INTO acc_commission_rate
    (OPERATOR_CODE, ROUTE_CODE, PLAN_TIER, MIN_AMOUNT, MAX_AMOUNT, RATE_PCT, EFFECTIVE_FROM, EFFECTIVE_TO, NOTE)
SELECT src.OPERATOR_CODE, src.ROUTE_CODE, '*', 0.00, NULL, src.RATE_PCT, CURDATE(), NULL, src.NOTE
FROM (
  SELECT 'ARTL'    AS OPERATOR_CODE, 'DIRECT'  AS ROUTE_CODE, 3.000000 AS RATE_PCT, 'Provisional - from memory, confirm with the business' AS NOTE
  UNION ALL SELECT 'ARTL',    'A1TOPUP', 0.800000, 'A1Topup portal, My Commission screen'
  UNION ALL SELECT 'RJIO',    'DIRECT',  2.500000, 'Provisional - from memory, confirm with the business'
  UNION ALL SELECT 'RJIO',    'A1TOPUP', 0.800000, 'A1Topup portal, My Commission screen'
  UNION ALL SELECT 'VI',      'DIRECT',  2.500000, 'Provisional - from memory, confirm with the business. Was IDEA and VDFN, identical rates (log 2.4)'
  UNION ALL SELECT 'VI',      'A1TOPUP', 3.300000, 'A1Topup portal. Was IDEA and VDFN, identical rates (log 2.4)'
  UNION ALL SELECT 'BSNL',    'A1TOPUP', 3.000000, 'A1Topup portal - STV and TOPUP both 3.00'
  UNION ALL SELECT 'DISHTV',  'A1TOPUP', 3.600000, 'A1Topup portal, My Commission screen'
  UNION ALL SELECT 'VIDCTV',  'A1TOPUP', 4.100000, 'A1Topup portal, My Commission screen'
  UNION ALL SELECT 'AIRLTV',  'A1TOPUP', 4.000000, 'A1Topup portal, My Commission screen'
  UNION ALL SELECT 'TATASKY', 'A1TOPUP', 3.500000, 'A1Topup portal, My Commission screen'
  UNION ALL SELECT 'BIGTV',   'A1TOPUP', 0.000000, 'PROVISIONAL ZERO - absent from the portal list. Not a guess: sell one and read the portal debit (log 8.4)'
) AS src
WHERE NOT EXISTS (
    SELECT 1 FROM acc_commission_rate r
     WHERE r.OPERATOR_CODE = src.OPERATOR_CODE
       AND r.ROUTE_CODE    = src.ROUTE_CODE
       AND r.PLAN_TIER     = '*'
       AND r.MIN_AMOUNT    = 0.00
);

SELECT 'V25: complete.' AS Status;
