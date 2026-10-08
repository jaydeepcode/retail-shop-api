-- ============================================================================
-- V10: Operator Catalog
-- ============================================================================
-- Spec: design-domain.md §6.3 (the V10 row) and §3.2.
--
-- The lookup stays the CODE AUTHORITY; rc_operator is the OPERATOR ROW. The
-- recharge module reads only rc_operator. This split exists because
-- rc_option_lookup_dtl's primary key includes rc_opt_sequence, so (type, code)
-- is not unique by construction there and nothing can soundly hang config off
-- "the row for code X" (§3.1 fact 2).
--
-- SAFE ALONGSIDE THE LEGACY WRITER: adds a unique key the data already
-- satisfies, two inactive lookup rows, and two new empty tables. No existing
-- row is modified.
-- ============================================================================

SELECT 'V10: building the operator catalog...' AS Status;

-- ============================================================================
-- 1. Make (type, option) unique on the lookup
-- ============================================================================
-- Verified safe before writing this: all 31 lookup rows across MOBL (12),
-- TVOPR (6) and SALCT (13) have distinct (rc_option_type, rc_option) pairs, so
-- this constrains nothing that exists. It is what lets rc_operator's composite
-- foreign key point at the lookup at all — MySQL requires the referenced
-- columns to be covered by a unique index, and the primary key does not
-- qualify because it carries rc_opt_sequence as a third column.
SET @idx_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'rc_option_lookup_dtl'
      AND INDEX_NAME = 'uk_opt_type_option'
);
SET @sql = IF(
    @idx_exists = 0,
    'ALTER TABLE rc_option_lookup_dtl
        ADD UNIQUE KEY uk_opt_type_option (rc_option_type, rc_option)',
    'SELECT ''uk_opt_type_option exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================================
-- 2. The UNKNOWN sentinels
-- ============================================================================
-- Mandatory, not defensive: 8,769 recharge lines carry no operator at all and
-- 386 more carry a bare 'SRVTE' that resolves to no operator. Those 9,155 rows
-- need somewhere to land in V15 or the backfill cannot satisfy rc_recharge's
-- NOT NULL operator column.
--
-- 'UNKNOWN' is exactly 7 characters, so it fits rc_option's varchar(7). This
-- mirrors the repo's existing habit of 'UNKNOWN' sentinels in ref_storage_type,
-- ref_pump_type and ref_trip_status (V3:163, 175, 186) — the house answer to
-- unmappable legacy data.
--
-- rc_opt_sw = 0 so the legacy application never offers them in a picker.
INSERT IGNORE INTO rc_option_lookup_dtl
    (rc_option_type, rc_option, rc_opt_descr, rc_opt_sw, rc_opt_sequence) VALUES
    ('MOBL',  'UNKNOWN', 'Unknown Operator', 0, 99),
    ('TVOPR', 'UNKNOWN', 'Unknown Operator', 0, 99);

-- ============================================================================
-- 3. rc_operator and rc_operator_preset (§3.2)
-- ============================================================================
CREATE TABLE IF NOT EXISTS rc_operator (
    OPERATOR_CODE      VARCHAR(20) NOT NULL,
    RECHARGE_KIND      VARCHAR(10) NOT NULL,
    LOOKUP_TYPE        VARCHAR(5)  NOT NULL,
    LOOKUP_CODE        VARCHAR(7)  NOT NULL,
    DISPLAY_NAME       VARCHAR(45) NOT NULL,
    DEFAULT_ROUTE_CODE VARCHAR(20) NOT NULL,
    IS_ACTIVE          BOOLEAN     NOT NULL DEFAULT TRUE,
    SORT_ORDER         INT         NOT NULL DEFAULT 0,
    RETIRED_ON         DATE        NULL,
    UPDATED_AT         TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UPDATED_BY         INT         NULL,
    PRIMARY KEY (OPERATOR_CODE),
    UNIQUE KEY uk_operator_lookup (LOOKUP_TYPE, LOOKUP_CODE),
    -- Serves the operator picker (§7.2): leading equality on kind, then the
    -- active filter, then the display order — a covering path for the whole
    -- query rather than a scan of thirteen rows that grows with the catalog.
    KEY idx_operator_kind_active (RECHARGE_KIND, IS_ACTIVE, SORT_ORDER),
    CONSTRAINT fk_operator_lookup FOREIGN KEY (LOOKUP_TYPE, LOOKUP_CODE)
        REFERENCES rc_option_lookup_dtl (rc_option_type, rc_option),
    CONSTRAINT fk_operator_route FOREIGN KEY (DEFAULT_ROUTE_CODE)
        REFERENCES ref_recharge_route (CODE),
    CONSTRAINT fk_operator_kind FOREIGN KEY (RECHARGE_KIND)
        REFERENCES ref_recharge_kind (CODE),
    CONSTRAINT fk_operator_updby FOREIGN KEY (UPDATED_BY)
        REFERENCES rc_user (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Preset amounts get a child table rather than a JSON column because
-- requirement 10 says this config is edited in tables with no screen — i.e. by
-- hand in SQL — and ordered scalars are far easier to edit as rows than inside
-- a JSON document.
CREATE TABLE IF NOT EXISTS rc_operator_preset (
    OPERATOR_CODE VARCHAR(20)   NOT NULL,
    AMOUNT        DECIMAL(38,2) NOT NULL,
    SORT_ORDER    INT           NOT NULL DEFAULT 0,
    IS_ACTIVE     BOOLEAN       NOT NULL DEFAULT TRUE,
    PRIMARY KEY (OPERATOR_CODE, AMOUNT),
    CONSTRAINT fk_preset_operator FOREIGN KEY (OPERATOR_CODE)
        REFERENCES rc_operator (OPERATOR_CODE) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================================
-- 4. Seed rc_operator from the lookup
-- ============================================================================
-- SRVTE* codes are EXCLUDED. They are routes in disguise, not operators: a
-- 'SRVTEA' row means "an Airtel recharge, put through Saravate", so V15 splits
-- it into OPERATOR_CODE='ARTL' plus a route, and the SRVTE* codes themselves
-- never become operators (§3.2's seed mapping table).
--
-- OPERATOR_CODE = LOOKUP_CODE for every real operator. The two sentinels
-- become MOBL_UNKNOWN and TVOPR_UNKNOWN, because OPERATOR_CODE is the primary
-- key and both kinds use the lookup code 'UNKNOWN'.
--
-- ⚠ CORRECTION TO design-domain.md §3.2. It specifies OPERATOR_CODE as
-- VARCHAR(10) and justifies the width with "(hence varchar(10))" immediately
-- after naming MOBL_UNKNOWN and TVOPR_UNKNOWN as the sentinel codes. Those
-- strings are 12 and 13 characters. At VARCHAR(10) they truncate to
-- 'MOBL_UNKNO' and 'TVOPR_UNKN', and because the seed was written as
-- INSERT IGNORE the truncation came through as a warning rather than an error:
-- the row COUNT was right, every foreign key resolved, and the codes were
-- silently wrong. Caught by RechargeMigrationTest asserting the codes
-- themselves rather than just the count.
--
-- Widened to VARCHAR(20), which also matches the width this design already
-- uses for ROUTE_CODE and STATUS_CODE. The seeds below are no longer
-- INSERT IGNORE, so a future oversized code fails loudly instead.
--
-- TWO DOWNSTREAM CONSEQUENCES for migrations not in this slice:
--   * design-ledger.md §8.2 says acc_account.OPERATOR_CODE is VARCHAR(10) and
--     foreign-keys here. V20 must declare it VARCHAR(20) or it fails errno 150.
--   * §4.6/V18 adds FK rc_dishtv_dtls.DISHTV_TYP → rc_operator and sets one row
--     to 'TVOPR_UNKNOWN'. DISHTV_TYP is varchar(10) in production, so V18 must
--     widen it to VARCHAR(20) first — otherwise that write truncates too.
--
-- IS_ACTIVE comes from rc_opt_sw, which already carries RNRL as 0 (Reliance,
-- 5 historical rows), so the design's "RNRL.IS_ACTIVE=0" needs no special case.
--
-- DEFAULT_ROUTE_CODE is DIRECT for all of them. V16 is what repoints the
-- operators the client routes through A1 — it has the gate that proves the
-- route backfill was correct first.
INSERT INTO rc_operator
    (OPERATOR_CODE, RECHARGE_KIND, LOOKUP_TYPE, LOOKUP_CODE,
     DISPLAY_NAME, DEFAULT_ROUTE_CODE, IS_ACTIVE, SORT_ORDER)
SELECT
    IF(d.rc_option = 'UNKNOWN', CONCAT(d.rc_option_type, '_UNKNOWN'), d.rc_option),
    CASE d.rc_option_type WHEN 'MOBL' THEN 'MOBILE' WHEN 'TVOPR' THEN 'TV' END,
    d.rc_option_type,
    d.rc_option,
    d.rc_opt_descr,
    'DIRECT',
    d.rc_opt_sw,
    d.rc_opt_sequence
FROM rc_option_lookup_dtl d
WHERE d.rc_option_type IN ('MOBL', 'TVOPR')
  AND d.rc_option NOT LIKE 'SRVTE%'
ON DUPLICATE KEY UPDATE OPERATOR_CODE = rc_operator.OPERATOR_CODE;

-- ============================================================================
-- 5. Seed preset amounts
-- ============================================================================
-- ⚠ THESE AMOUNTS ARE THE DESIGN'S PROPOSAL AND STILL NEED THE CLIENT'S
-- CONFIRMATION — design-domain.md §6.3's V10 row says "confirm with the
-- client" and that has not happened yet. They are seeded anyway because an
-- empty preset list makes the counter screen unusable, and because this is
-- config-in-tables by design (requirement 10): changing them is an UPDATE, not
-- a release. Treat the values as provisional, not as a decision.
--
-- Seeded only for ACTIVE operators, so RNRL gets none.
INSERT INTO rc_operator_preset (OPERATOR_CODE, AMOUNT, SORT_ORDER)
SELECT o.OPERATOR_CODE, p.AMOUNT, p.SORT_ORDER
FROM rc_operator o
JOIN (
    SELECT 'MOBILE' AS KIND,  10.00 AS AMOUNT, 10 AS SORT_ORDER
    UNION ALL SELECT 'MOBILE',  20.00, 20
    UNION ALL SELECT 'MOBILE',  50.00, 30
    UNION ALL SELECT 'MOBILE', 100.00, 40
    UNION ALL SELECT 'MOBILE', 199.00, 50
    UNION ALL SELECT 'MOBILE', 239.00, 60
    UNION ALL SELECT 'MOBILE', 299.00, 70
    UNION ALL SELECT 'TV',     300.00, 10
    UNION ALL SELECT 'TV',     500.00, 20
) p ON p.KIND = o.RECHARGE_KIND
WHERE o.IS_ACTIVE = TRUE
ON DUPLICATE KEY UPDATE OPERATOR_CODE = rc_operator_preset.OPERATOR_CODE;

SELECT 'V10: complete.' AS Status;
