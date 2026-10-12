-- ============================================================================
-- V26: Ledger Views
-- ============================================================================
-- Spec: design-ledger.md §2.6, and §8.1's V25 row. Split into its own migration
-- so a view definition can change without touching a table.
--
-- SAFE ALONGSIDE THE LEGACY WRITER: a read-only view over acc_* tables.
--
-- THIS VIEW STORES NOTHING, AND THAT IS THE POINT (L5, FRD §8.4). Running
-- balances are derived, never stored, and there is no monthly-snapshot table.
-- At ~20k lines/year, seven forward years is ~140k rows: idx_line_account makes
-- this an index-only aggregate and it runs in single-digit milliseconds. §9 names
-- a materialised balance as over-engineering and says not to build one before
-- acc_voucher_line passes a few million rows — which, at this volume, is never.
--
-- ⚠ CORRECTS A DEFECT IN §2.6's PUBLISHED QUERY (design-ledger.md:528-541).
-- As written, THE SEAL FILTER IS INERT and unsealed vouchers are counted in
-- every balance. The published form is:
--
--     LEFT JOIN acc_voucher_line l ON l.ACCOUNT_CODE = a.ACCOUNT_CODE
--     LEFT JOIN acc_voucher      v ON v.VOUCHER_ID = l.VOUCHER_ID
--                                 AND v.SEALED_AT IS NOT NULL
--     ...
--     COALESCE(SUM(l.DR_AMOUNT), 0) AS TOTAL_DR
--
-- Trace one leg of an unsealed voucher. The join to acc_voucher_line matches on
-- ACCOUNT_CODE, so l is present. The join to acc_voucher fails its SEALED_AT
-- predicate, so v is NULL-extended — but because it is a LEFT JOIN the row
-- SURVIVES, and every SUM in the select list reads l, never v. The amount is
-- counted. The predicate only nulls columns nothing reads.
--
-- Moving it to a WHERE clause is not the fix: that turns the outer join inner
-- and silently drops every account with no postings, which on day one is every
-- account. So the seal test has to sit inside the aggregate, which is what this
-- version does. LedgerMigrationTest proves it by posting an unsealed voucher
-- with two legs and asserting the balance stays 0.00 — the published form
-- returns the leg amount there, so the assertion is what distinguishes them.
--
-- CREATE OR REPLACE rather than a guard: it is idempotent by construction and
-- re-running it is how a view definition gets corrected.
-- ============================================================================

SELECT 'V26: creating the ledger views...' AS Status;

-- ============================================================================
-- acc_account_balance (§2.6)
-- ============================================================================
-- Two further things worth noticing:
--
--   * NORMAL_BALANCE comes from ref_account_type, so the sign rule is DATA. An
--     asset's balance is DR - CR and a liability's is CR - DR, and neither is
--     written in Java.
--
--   * An unsealed voucher contributes nothing, so a half-built voucher is
--     invisible to every report rather than wrong in one. That is what makes the
--     three-step post (insert header, add legs, seal) safe to interrupt, and it
--     is the property the correction above exists to deliver.
CREATE OR REPLACE VIEW acc_account_balance AS
SELECT a.ACCOUNT_CODE, a.DISPLAY_NAME, a.ACCOUNT_TYPE, a.BUSINESS_CODE, a.REPORT_GROUP,
       t.NORMAL_BALANCE,
       COALESCE(SUM(CASE WHEN v.VOUCHER_ID IS NULL THEN 0 ELSE l.DR_AMOUNT END), 0) AS TOTAL_DR,
       COALESCE(SUM(CASE WHEN v.VOUCHER_ID IS NULL THEN 0 ELSE l.CR_AMOUNT END), 0) AS TOTAL_CR,
       CASE t.NORMAL_BALANCE
            WHEN 'D' THEN COALESCE(SUM(CASE WHEN v.VOUCHER_ID IS NULL THEN 0
                                            ELSE l.DR_AMOUNT - l.CR_AMOUNT END), 0)
            ELSE          COALESCE(SUM(CASE WHEN v.VOUCHER_ID IS NULL THEN 0
                                            ELSE l.CR_AMOUNT - l.DR_AMOUNT END), 0)
       END AS BALANCE
FROM       acc_account       a
JOIN       ref_account_type  t ON t.CODE = a.ACCOUNT_TYPE
LEFT JOIN  acc_voucher_line  l ON l.ACCOUNT_CODE = a.ACCOUNT_CODE
LEFT JOIN  acc_voucher       v ON v.VOUCHER_ID   = l.VOUCHER_ID AND v.SEALED_AT IS NOT NULL
GROUP BY a.ACCOUNT_CODE, a.DISPLAY_NAME, a.ACCOUNT_TYPE, a.BUSINESS_CODE, a.REPORT_GROUP, t.NORMAL_BALANCE;

SELECT 'V26: complete.' AS Status;
