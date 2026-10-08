-- ============================================================================
-- V20: Operator Catalog — Idea and Vodafone merge into Vi
-- ============================================================================
-- Spec: recharge-technical-decisions.md §2.4:84-105.
--
-- WHY THIS IS A RECHARGE-MODULE CHANGE SITTING IN THE LEDGER'S NUMBER RANGE.
-- It is not ledger work, and design-ledger.md:238 warns specifically against a
-- ledger migration reaching into rc_operator ("do not create rc_operator from a
-- ledger migration, or two migrations will race to own it"). It is here anyway
-- because it cannot be numbered anywhere else:
--
--   * spring.flyway.out-of-order=false (application.properties:24), so a
--     migration may only apply if its version is above everything applied.
--   * V13-V19 are reserved cutover work that is incompatible with the live
--     legacy writer (implementation-plan.md §0.2). Numbering this in that range
--     would make it un-runnable without also running the cutover set.
--   * V21's chart of accounts foreign-keys the operator code 'VI', so this must
--     land BEFORE it, not after.
--
-- So: own file, lowest number above the reserved range, nothing else in it. The
-- ledger chain that design-ledger.md §8.1 numbers V20-V26 is therefore V21-V27
-- in this repository, and V27/V28's opening-balance work shifts to V28/V29. That
-- renumbering is recorded in db/migration/README.md and in design-ledger.md §8.1.
--
-- WHAT IT DOES, AND WHY IT IS NEEDED AT ALL.
-- §2.4 establishes that the shop holds FOUR float wallets, not one per operator:
-- Airtel, Jio, Vi and A1Topup. The float account is resolved from operator AND
-- route together:
--
--     route = 'A1TOPUP'  ->  FLOAT_A1TOPUP
--     route = 'DIRECT'   ->  CONCAT('FLOAT_', OPERATOR_CODE)
--
-- That rule is computable at runtime and needs no mapping table — but ONLY once
-- IDEA and VDFN stop being two separate operators. §2.4:103-105: they "remain
-- separate active codes in rc_option_lookup_dtl from before the merger; the
-- balance is now a single Vi wallet — confirmed". Two codes pointing at one
-- wallet is the single case the derivation cannot express, so the merge is what
-- makes the mapping table unnecessary.
--
-- Confirmed with the owner 2026-10-08: going forward there is one Vi operator.
-- The separation is legacy only.
--
-- SAFE ALONGSIDE THE LEGACY WRITER (implementation-plan.md §0.2).
--   * The new rc_option_lookup_dtl row carries rc_opt_sw = 0, so the legacy
--     application never offers Vi in its picker. This is exactly V10's idiom for
--     the two UNKNOWN sentinels (V10 section 2).
--   * IS_ACTIVE on rc_operator is read only by the NEW module. The legacy app
--     reads rc_opt_sw, which is left untouched on the IDEA and VDFN rows, so
--     retiring them here changes nothing the legacy app sees.
--   * No rc_txn_* row is touched. rc_recharge is empty until V15.
--
-- HISTORY KEEPS ITS OWN OPERATOR. IDEA and VDFN are retired (IS_ACTIVE = 0),
-- never deleted: rc_recharge.OPERATOR_CODE foreign-keys them and V15's backfill
-- maps legacy SRVTEI/SRVTEV rows onto them. A retired operator still satisfies
-- the FK, so pre-cutover history stays truthful about which code was typed while
-- every future sale uses VI. No float account is derived for a retired operator,
-- which is correct — pre-D history is never posted to the ledger (L13).
--
-- ⚠ TWO CONSEQUENCES FOR MIGRATIONS NOT IN THIS SLICE:
--   * V15's backfill SPLITS BY DATE on the operator, decided 2026-10-09 and
--     recorded in log §15.8 / design-domain.md §3.2. Current-financial-year rows
--     get 'VI' -- including SRVTEI and SRVTEV, which also take route A1TOPUP --
--     while everything older keeps whichever code was typed. The front end only
--     shows current-FY data, so an inconsistency behind that boundary is
--     invisible and rewriting seven years of history buys nothing.
--
--     That is why the retirement below is an UPDATE and not a DELETE: those
--     older rows foreign-key IDEA and VDFN. A retired operator still satisfies
--     fk_rech_operator; IS_ACTIVE governs the picker, not referential integrity.
--
--     Note the float derivation is NEVER applied to those pre-D rows, so
--     CONCAT('FLOAT_','IDEA') naming no account is not a defect -- pre-D history
--     is not posted to the ledger at all (design-ledger.md L13).
--   * V16 is still what repoints the A1Topup operators' DEFAULT_ROUTE_CODE. This
--     migration leaves every operator on DIRECT, as V10 left them.
--
-- Idempotent throughout, following V9/V10.
-- ============================================================================

SELECT 'V20: merging Idea and Vodafone into Vi...' AS Status;

-- ============================================================================
-- 1. The lookup row
-- ============================================================================
-- rc_operator's composite FK fk_operator_lookup points at
-- rc_option_lookup_dtl (rc_option_type, rc_option), so the lookup row must
-- exist before the operator row.
--
-- rc_opt_sequence = 13 because MOBL's existing sequences run 1-12 and the
-- PRIMARY KEY is (rc_option_type, rc_option, rc_opt_sequence). Reusing Idea's 3
-- would collide with nothing, but 13 keeps the legacy table unambiguous. The
-- NEW module's display order is rc_operator.SORT_ORDER, set to 3 below so Vi
-- takes Idea's slot in the picker.
--
-- rc_option is VARCHAR(7); 'VI' is 2 characters.
INSERT IGNORE INTO rc_option_lookup_dtl
    (rc_option_type, rc_option, rc_opt_descr, rc_opt_sw, rc_opt_sequence) VALUES
    ('MOBL', 'VI', 'Vi', 0, 13);

-- ============================================================================
-- 2. The Vi operator
-- ============================================================================
-- DEFAULT_ROUTE_CODE = 'DIRECT', matching every other operator V10 seeded. V16
-- is what repoints the operators the shop routes through A1Topup, after the gate
-- that proves its route backfill was correct.
--
-- Not INSERT IGNORE: an oversized or mistyped code must fail loudly rather than
-- arrive truncated. That is the lesson V10's header records.
INSERT INTO rc_operator
    (OPERATOR_CODE, RECHARGE_KIND, LOOKUP_TYPE, LOOKUP_CODE,
     DISPLAY_NAME, DEFAULT_ROUTE_CODE, IS_ACTIVE, SORT_ORDER)
VALUES
    ('VI', 'MOBILE', 'MOBL', 'VI', 'Vi', 'DIRECT', TRUE, 3)
ON DUPLICATE KEY UPDATE OPERATOR_CODE = rc_operator.OPERATOR_CODE;

-- ============================================================================
-- 3. Retire Idea and Vodafone
-- ============================================================================
-- Guarded on IS_ACTIVE = TRUE so a re-run does not overwrite RETIRED_ON with a
-- later date.
--
-- CURDATE() rather than a literal, for the same reason V16 uses its own run date
-- for SARAVATE (design-ledger-api.md §5): IS_ACTIVE = 0 does the real work and
-- the date is descriptive.
UPDATE rc_operator
   SET IS_ACTIVE = FALSE,
       RETIRED_ON = CURDATE()
 WHERE OPERATOR_CODE IN ('IDEA', 'VDFN')
   AND IS_ACTIVE = TRUE;

-- ============================================================================
-- 4. Move the preset amounts
-- ============================================================================
-- V10 seeded 7 MOBILE presets per active operator and left them provisional
-- ("THESE AMOUNTS ARE THE DESIGN'S PROPOSAL AND STILL NEED THE CLIENT'S
-- CONFIRMATION"). Vi inherits the same seven; the per-operator amounts log
-- §14.10 measured are a later refinement, not this migration's business.
--
-- The retired operators' presets are DELETED rather than left behind, because
-- a preset on an inactive operator is unreachable config that would still show
-- up in any count of the picker's contents. 45 - 14 + 7 = 38.
DELETE FROM rc_operator_preset WHERE OPERATOR_CODE IN ('IDEA', 'VDFN');

INSERT INTO rc_operator_preset (OPERATOR_CODE, AMOUNT, SORT_ORDER)
SELECT 'VI', p.AMOUNT, p.SORT_ORDER
FROM (
    SELECT  10.00 AS AMOUNT, 10 AS SORT_ORDER
    UNION ALL SELECT  20.00, 20
    UNION ALL SELECT  50.00, 30
    UNION ALL SELECT 100.00, 40
    UNION ALL SELECT 199.00, 50
    UNION ALL SELECT 239.00, 60
    UNION ALL SELECT 299.00, 70
) p
ON DUPLICATE KEY UPDATE OPERATOR_CODE = rc_operator_preset.OPERATOR_CODE;

-- ============================================================================
-- 5. Assert the merge landed
-- ============================================================================
-- Cheap, and it is the whole premise V21's float seeds rest on: if VI is absent
-- or IDEA/VDFN are still active, the four-wallet derivation silently becomes a
-- five- or six-wallet one.
SET @vi_active = (
    SELECT COUNT(*) FROM rc_operator
     WHERE OPERATOR_CODE = 'VI' AND IS_ACTIVE = TRUE AND RECHARGE_KIND = 'MOBILE'
);
SET @merged_still_active = (
    SELECT COUNT(*) FROM rc_operator
     WHERE OPERATOR_CODE IN ('IDEA', 'VDFN') AND IS_ACTIVE = TRUE
);

-- ⚠ SIGNAL CANNOT BE RUN THROUGH PREPARE/EXECUTE. MySQL rejects it with
-- "ERROR 1295: This command is not supported in the prepared statement protocol
-- yet", so the repo's usual dynamic-SQL idiom cannot carry an assertion. The
-- failure is invisible until the assertion actually fails, because the passing
-- branch prepares a harmless SELECT. A throwaway procedure is the way to signal
-- conditionally outside a trigger, and it reports the real message.
DROP PROCEDURE IF EXISTS v20_assert;

DELIMITER $$
CREATE PROCEDURE v20_assert(IN p_failures TEXT)
BEGIN
    IF p_failures <> '' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = p_failures;
    END IF;
END$$
DELIMITER ;

SET @failures = CONCAT_WS(' | ',
    IF(@vi_active = 1, NULL, 'V20: operator VI is missing or inactive'),
    IF(@merged_still_active = 0, NULL, 'V20: IDEA/VDFN still active after the merge')
);

CALL v20_assert(COALESCE(@failures, ''));
DROP PROCEDURE v20_assert;

SELECT 'V20: complete.' AS Status;
