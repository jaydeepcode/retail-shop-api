-- ============================================================================
-- V28: Recharge Counter Prerequisites
-- ============================================================================
-- Spec: design-api.md §3.3, design-domain.md §4.5, §6.4, log §14.10, §15.11.
--
-- Four things S3 needs that V9-V12 did not build. Each one was decided in a
-- document and simply never reached a migration; none of them is a new design
-- decision. The headers below name the decision and what it replaces.
--
--   1. rc_recharge.SURCHARGE        — the shop's own charge, as its own amount
--   2. rc_setting surcharge defaults — DTH on, mobile off (log §15.11)
--   3. rc_operator_preset           — V10's provisional amounts replaced with
--                                     the measured per-operator sets (§14.10)
--   4. rc_credit_req                — §4.5's three columns, and §6.4's index
--
-- ⚠ NUMBERING. design-ledger.md §8.1's opening-balance migrations were moved to
-- V28/V29 by implementation-plan.md §0.5 when V20 was taken by the Vi merge.
-- They move again, to V29/V30: they are S6 go-live work and apply after this
-- one regardless, while out-of-order=false means S3 cannot reach past them.
--
-- SAFE ALONGSIDE THE LEGACY WRITER, which is why it can ship before cutover:
--   * rc_recharge, rc_setting and rc_operator_preset are tables created by
--     V9-V12 and are empty or config-only. The legacy application does not
--     know they exist.
--   * rc_credit_req gains three NULLABLE columns. The legacy writer inserts
--     without naming them and keeps working unchanged — the same argument V11
--     records for rc_txn_header, and here it is simpler because there is no
--     NOT NULL and no default to reason about.
--   * Dropping CRED_DTTM_UNIQUE REMOVES a constraint. Nothing that inserted
--     successfully before can fail afterwards.
--
-- ⚠ THIS MIGRATION DOES NOT TOUCH MONEY OR HISTORY. No row's amount, type or
-- date changes. V13-V19 remain cutover-day work and must not run here.
-- ============================================================================

SELECT 'V28: recharge counter prerequisites...' AS Status;

-- ============================================================================
-- 1. rc_recharge.SURCHARGE (design-api.md §3.3, log §10.2, §15.11)
-- ============================================================================
-- The shop's own charge on a recharge -- Rs 10 on DTH -- kept as its OWN amount
-- and never folded into AMOUNT.
--
-- ⚠ THIS COLUMN WAS MISSING, and the gap spanned three documents. log §10.2
-- required "the surcharge as its own amount on the recharge"; §14.12 item 3
-- corrected design-api.md to carry a per-line surcharge field; V21 seeded the
-- INC_RCHG_SURCHARGE account it credits. But design-domain.md §4.4's DDL never
-- gained the column, so V12 faithfully created rc_recharge without it. The
-- income account existed with nothing to post to it.
--
-- Why a column on rc_recharge rather than an ANTV line in rc_txn_details, which
-- is how history records it: the posting rule needs the surcharge PER RECHARGE,
-- because Cr INC_RCHG_SURCHARGE belongs to the completion voucher of one
-- recharge (log §15.11), while an rc_txn_details line belongs to the basket.
-- With two recharges in one basket -- 57 FY2026-27 baskets hold two (§14.10) --
-- a basket-level line cannot say which recharge the charge was for. History
-- keeps its ANTV lines; new sales carry the amount on the recharge.
--
-- NOT NULL DEFAULT 0 is safe unconditionally: the table is empty (V15's
-- backfill is cutover work and has not run), so there is nothing to default.
SET @col_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'rc_recharge' AND COLUMN_NAME = 'SURCHARGE'
);
SET @sql = IF(
    @col_exists = 0,
    'ALTER TABLE rc_recharge
        ADD COLUMN SURCHARGE DECIMAL(38,2) NOT NULL DEFAULT 0.00 AFTER AMOUNT,
        ADD CONSTRAINT ck_rech_surcharge CHECK (SURCHARGE >= 0)',
    'SELECT ''rc_recharge.SURCHARGE exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ck_rech_surcharge is >= 0, NOT "= 0 for mobile". log §15.11 is explicit that
-- the field stays editable and that a mobile line may still carry one if staff
-- type it -- only the DEFAULT is DTH-only. A CHECK keyed to RECHARGE_KIND would
-- make the 198 historical mobile cases impossible to reproduce, which §15.11
-- says is not the intent.

-- ============================================================================
-- 2. The surcharge defaults (log §15.11, requirement 10)
-- ============================================================================
-- design-api.md §3.3: "surcharge defaults to the configured amount for the
-- service type when the field is absent -- Rs 10 on TV, zero on mobile". There
-- was no configured amount to read; these are it.
--
-- Two keys rather than one "surcharge.default.tv=10" pair, because §10.2's
-- verifiability argument applies to the mobile side too: a zero that is stated
-- is checkable, and a zero that is absent is indistinguishable from a missing
-- row. Settings are read through rc_setting by design (requirement 10), so
-- changing either is an UPDATE rather than a release.
INSERT IGNORE INTO rc_setting (SETTING_KEY, SETTING_VALUE, VALUE_TYPE, DESCRIPTION) VALUES
    ('surcharge.default.tv', '10.00', 'DECIMAL',
     'Default shop surcharge on a DTH/TV recharge, in rupees; editable per line'),
    ('surcharge.default.mobile', '0.00', 'DECIMAL',
     'Default shop surcharge on a mobile recharge; zero by decision, log 15.11'),

    -- ⚠ THE "FLAG" log 2.6 ASSUMES, WHICH DID NOT EXIST. 2.6 decides that three
    -- rs_cust_dtls rows are not customers -- 269 Janata Sahakari Bank, 409 Hdfc
    -- Bank, 323 Bankof India4425, Rs 49,211 between them -- and says "the three
    -- are flagged as non-customers so the credit book filters them out". No
    -- migration ever added a flag, and rs_cust_dtls has no column for one.
    --
    -- A setting rather than a column, for two reasons. Adding a column to
    -- rs_cust_dtls means an UPDATE against the book of record to set it, which is
    -- S6's remit; and the list is configuration of the same kind as
    -- bank.default.account (log 9.3), which 9.3 already decided belongs in
    -- rc_setting rather than in code.
    --
    -- Their origin, confirmed: the legacy app had no concept of a UPI receipt, so
    -- when cash was not in the drawer because it had gone to the bank, the amount
    -- was recorded against a "customer" named after the bank. 996 CRDT lines point
    -- at 269 and are UPI markers, not credit. Going forward UPI Clearing handles
    -- this properly, so the list is closed and should never grow.
    --
    -- ⚠ These rows must NOT be deleted: those 996 rows are the only recoverable
    -- record of which historical sales were UPI rather than cash, and V13's
    -- backfill reads them to derive the historical payment method (log 9.4).
    ('credit.ledger.customers', '269,323,409', 'STRING',
     'Comma-separated rs_cust_dtls CUST_IDs that are ledger accounts, not people');

-- ============================================================================
-- 3. rc_operator_preset -- the measured amounts (log §14.10)
-- ============================================================================
-- ⚠ THIS REPLACES V10's SEEDS, which V10's own header calls "the design's
-- proposal and still need the client's confirmation ... Treat the values as
-- provisional, not as a decision." §14.10 is that confirmation, measured
-- against FY2026-27 on the restore, and it contradicts the proposal in two
-- structural ways rather than merely adjusting numbers:
--
--   * Presets are PER OPERATOR, not one list for every mobile operator. V10
--     gave all of them 10/20/50/100/199/239/299.
--   * TV GETS NO PRESETS AT ALL. Dish TV's commonest amount is 16% of its
--     volume against mobile's 78%, so there is no short list to offer; the
--     screen uses a +/- Rs 10 stepper instead. V10's TV 300/500 "match
--     nothing" (§14.10).
--
-- The measured sets, with the share of each operator's FY2026-27 volume the
-- four cover:
--     RJIO  299 (63%) / 123 / 349 / 239   = 86%
--     ARTL  199 / 299 / 349 / 26          = 84%
--     BSNL  147 (66%) / 199 / 219 / 141   = 92%
--
-- ⚠ VI DELIBERATELY GETS NONE, AND THIS IS THE ONE FIGURE WORTH A SECOND LOOK.
-- §14.10 measured the pre-merge VI code at 23 recharges -- "too thin to preset"
-- -- and measured IDEA separately at 199 (64%) / 299 / 26 / 179 = 85%. V20 then
-- merged IDEA and VDFN into VI (log §15.2), so VI now inherits that volume
-- while the presets measured for it belong to a retired code. Implementing
-- §14.10 literally therefore leaves the shop's third-largest float (Vi,
-- Rs 39,606 FY2026-27 consumption, §14.9) with no amount chips.
--
-- Implemented literally anyway, because §14.10 is a recorded decision and
-- changing it is the owner's call, not this migration's. If the answer is that
-- VI should inherit IDEA's set, it is one INSERT into a table built to be
-- hand-edited (requirement 10) -- no release, no code change. Raised in the PR.
--
-- SORT_ORDER follows the measured frequency, commonest first, so the leftmost
-- chip is the one most often wanted.
DELETE FROM rc_operator_preset;

INSERT INTO rc_operator_preset (OPERATOR_CODE, AMOUNT, SORT_ORDER) VALUES
    ('RJIO', 299.00, 10),
    ('RJIO', 123.00, 20),
    ('RJIO', 349.00, 30),
    ('RJIO', 239.00, 40),
    ('ARTL', 199.00, 10),
    ('ARTL', 299.00, 20),
    ('ARTL', 349.00, 30),
    ('ARTL',  26.00, 40),
    ('BSNL', 147.00, 10),
    ('BSNL', 199.00, 20),
    ('BSNL', 219.00, 30),
    ('BSNL', 141.00, 40);

-- ============================================================================
-- 4. rc_credit_req -- §4.5's columns and §6.4's index
-- ============================================================================
-- ⚠ THIS TAKES WORK FROM V17, AND THAT IS THE ONE REAL DEPARTURE IN THIS FILE.
-- design-domain.md §6.3 assigns all of rc_credit_req's extensions to
-- V17__Credit_request_extensions.sql. V17 sits inside the reserved V13-V19
-- block, and the reason S3 cannot simply run it is ORDERING, not V17's own
-- contents: nothing in V17 would trouble the legacy writer, but
-- out-of-order=false means reaching V17 requires V13 and V14 to apply first --
-- V13 moves the CRDT rows out of the item table and V14 adds
-- CHECK (amt_returned >= 0), which breaks the legacy app's next UPI write.
-- So the parts S3 needs are taken forward here and V17's scope shrinks.
--
-- WHAT V17 KEEPS, when cutover writes it: the two placeholder rs_cust_dtls rows
-- for CUST_ID 191 and 311, the FK CUST_ID -> rs_cust_dtls they enable, and the
-- FK req_type -> ref_credit_req_type. Those are listed at the end of this
-- section with the reason each one stays behind. V17 must therefore guard its
-- remaining ALTERs the way this file does, or it will fail on a database that
-- has already had V28.
--
-- ⚠ ONE STALE COMMENT FIXED ALONGSIDE: db/legacy-baseline.sql said
-- CRED_DTTM_UNIQUE is reproduced "because V17 drops it permanently". It is this
-- migration that drops it; the fixture now says so.
--
-- CRED_DTTM_UNIQUE is the blocking half, and it blocks the counter rather than
-- merely annoying it. The index is UNIQUE on CRED_DTTM ALONE, so it forbids two
-- DIFFERENT customers being given credit in the same second -- "not a business
-- rule; it is an accident" (§6.4). It holds today only because one operator has
-- been typing at human speed for seven years: 4,301 of 4,301 distinct. The
-- counter writes a credit row inside the settle transaction, so the first time
-- two staff settle concurrently it throws DuplicateKeyException from inside a
-- transaction that is carrying a sale. WaterTransactionService shares the bug
-- today via populateCreditDebitRecord's LocalDateTime.now() with no retry.
--
-- Dropped for good, not for the migration, per §6.4's recommendation over the
-- brief's "drop for migration, restore after". The honest replacement is
-- idx_credit_cust_dttm (CUST_ID, CRED_DTTM), which serves the credit book and
-- the ageing query and constrains nothing.
SET @idx_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'rc_credit_req' AND INDEX_NAME = 'CRED_DTTM_UNIQUE'
);
SET @sql = IF(
    @idx_exists > 0,
    'ALTER TABLE rc_credit_req DROP INDEX CRED_DTTM_UNIQUE',
    'SELECT ''CRED_DTTM_UNIQUE already gone'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'rc_credit_req' AND INDEX_NAME = 'idx_credit_cust_dttm'
);
SET @sql = IF(
    @idx_exists = 0,
    'ALTER TABLE rc_credit_req ADD KEY idx_credit_cust_dttm (CUST_ID, CRED_DTTM)',
    'SELECT ''idx_credit_cust_dttm exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- The three columns. All NULLABLE and all staying NULL for the 4,301
-- historical rows: no record of who extended a legacy credit exists anywhere,
-- which is §8.8's finding, so there is nothing to backfill from. TXN_ID is
-- back-fillable from the 2,844 CRDT rows, but that is V13's job on cutover day
-- and not this migration's.
SET @col_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'rc_credit_req' AND COLUMN_NAME = 'CRE_BY'
);
SET @sql = IF(
    @col_exists = 0,
    'ALTER TABLE rc_credit_req
        ADD COLUMN CRE_BY INT          NULL,
        ADD COLUMN NOTE   VARCHAR(255) NULL,
        ADD COLUMN TXN_ID MEDIUMINT    NULL,
        ADD KEY idx_credit_txn (TXN_ID),
        ADD CONSTRAINT fk_credit_creby FOREIGN KEY (CRE_BY)
            REFERENCES rc_user (id) ON DELETE SET NULL,
        ADD CONSTRAINT fk_credit_txn FOREIGN KEY (TXN_ID)
            REFERENCES rc_txn_header (TXN_ID)',
    'SELECT ''rc_credit_req.CRE_BY exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ⚠ WHAT STAYS WITH V17, and why each one cannot come forward:
--
--   * The two placeholder rs_cust_dtls rows (CUST_ID 191 and 311) and the FK
--     CUST_ID -> rs_cust_dtls they enable. §6.3's V17 row inserts them as
--     'Deleted customer 191'/'311'; §4.5 allows the FK only "after fixing
--     CUST_ID 191 and 311", the two orphans §8.13 records. Inserting rows into
--     rs_cust_dtls is writing to the book of record, and S6 is the only slice
--     that touches historical data (implementation-plan.md §1.2). Without the
--     placeholders the FK fails errno 1452 today. S3 does not need it: the
--     counter resolves a customer through CustomerDirectoryService before it
--     writes a credit row, so the referential guarantee it relies on is a
--     service check on the way in, not a constraint on legacy rows.
--
--   * FK req_type -> ref_credit_req_type. V9 created and seeded that reference
--     table with two codes, but the constraint requires every one of the 4,301
--     existing req_type values to already be one of them, and that has not been
--     verified against the live column -- only against the brief. An unverified
--     constraint on the book of record is exactly the shape of failure that
--     stops a migration halfway, so it waits for the slice that is allowed to
--     repair what it finds.
--
-- Both are recorded in the README as outstanding, with V17 named, rather than
-- silently dropped.

-- ============================================================================
-- 5. DEFAULT_ROUTE_CODE for the operators with no direct connection
-- ============================================================================
-- ⚠ ALSO TAKEN FROM A CUTOVER MIGRATION, and without it 37% of recharges fail
-- at the counter on day one. V10 seeds DEFAULT_ROUTE_CODE = 'DIRECT' for every
-- operator, and its own header says why: "V16 is what repoints the operators the
-- client routes through A1." V16 is cutover work behind V13/V14.
--
-- What that leaves, measured against V25's seeded rates:
--
--   ARTL, RJIO, VI   DIRECT and A1TOPUP rates both seeded  -> DIRECT is right
--   BSNL             A1TOPUP only                          -> DIRECT is WRONG
--   DISHTV, VIDCTV,  A1TOPUP only                          -> DIRECT is WRONG
--   AIRLTV, TATASKY,
--   BIGTV
--
-- So a BSNL or TV recharge taken today would default to DIRECT and then fail
-- twice over at the completion post: no rate row, so log 8.2 refuses the income
-- leg; and the float account derives to FLOAT_BSNL or FLOAT_DISHTV, neither of
-- which exists, because the shop holds FOUR wallets and the derivation
-- CONCAT('FLOAT_', OPERATOR_CODE) only applies on the DIRECT route (log 15.1).
--
-- log 14.6 states the rule plainly: "The default stays per operator, never
-- globally DIRECT: BSNL plus all five TV operators have no direct connection,
-- which is 382 of 1,037 FY recharges (37%). A global DIRECT default would send
-- every one of them into 8.2's refusal." 2.4:86 gives BSNL no direct connection
-- at all. This is that rule applied to the data rather than a new decision.
--
-- Derived from the seeded rates rather than listed by hand, so the two cannot
-- drift: an operator whose only open rate row is on A1TOPUP routes through
-- A1TOPUP. If a DIRECT rate is ever added for BSNL, re-running this logic would
-- leave it alone -- which is correct, because by then it would have a direct
-- connection.
UPDATE rc_operator o
   SET o.DEFAULT_ROUTE_CODE = 'A1TOPUP'
 WHERE o.IS_ACTIVE = TRUE
   AND o.DEFAULT_ROUTE_CODE = 'DIRECT'
   AND NOT EXISTS (
       SELECT 1 FROM acc_commission_rate r
        WHERE r.OPERATOR_CODE = o.OPERATOR_CODE
          AND r.ROUTE_CODE = 'DIRECT'
          AND r.EFFECTIVE_TO IS NULL
   )
   AND EXISTS (
       SELECT 1 FROM acc_commission_rate r
        WHERE r.OPERATOR_CODE = o.OPERATOR_CODE
          AND r.ROUTE_CODE = 'A1TOPUP'
          AND r.EFFECTIVE_TO IS NULL
   );

-- ⚠ V16 MUST NOT REDO THIS. Its own row in 6.3 ends with "insert A1TOPUP as
-- active and set it as DEFAULT_ROUTE_CODE for the operators the client routes
-- through A1". A1TOPUP is already active (V9 seeds it, so V15's backfill can
-- foreign-key it), and the defaults are set here. V16 keeps the SARAVATE
-- retirement and the rc_opt_sw updates, which are about history.

SELECT 'V28: complete.' AS Status;
