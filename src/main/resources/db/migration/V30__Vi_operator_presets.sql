-- ============================================================================
-- V30 — Vi's amount presets, decided by the owner
-- ============================================================================
-- WHAT THIS CLOSES. log §14.10 recorded, as a known and open item, that "Vi
-- offers no amount presets — staff type the amount. One INSERT fixes it when
-- the owner decides the amounts." This is that INSERT. The amounts are
-- 199 / 239 / 299.
--
-- ⚠ WHY A NEW MIGRATION RATHER THAN AN EDIT TO V20. This change was first made
-- on 2026-10-22 by editing V20__Operator_catalog_vi_merge.sql in place, and
-- that was reverted because editing an APPLIED migration does two things, both
-- bad and neither of them the intended one:
--
--   1. It changes V20's checksum, so `flyway validate` fails on every database
--      that already carries V20 — the developer restore (applied 2026-10-09)
--      and production. Flyway validates before it migrates, so the application
--      does not start at all. This is the same failure db/migration/README.md
--      documents at length for V8, and it cost a boot to rediscover.
--   2. It does not apply. Flyway never re-runs an applied migration, so V20's
--      new text would sit in the repository having changed nothing, while a
--      database built from scratch got different preset rows from one migrated
--      forward. Two databases, same version number, different data.
--
-- `flyway repair` is NOT the fix here either, and the distinction matters.
-- Repair was right for V8 because that file was a RECONSTRUCTION of what had
-- genuinely run. Here the old and new text do different things, so repair would
-- record "V20 as applied = the new version" on a database where the old version
-- ran — a history that is simply false.
--
-- ⚠ THE CAP MOVES WITH THIS FILE. spring.flyway.target went 29 -> 30 in
-- application.properties and application-container.properties, and
-- local-dev/gate-preflight.sh's EXPECTED_HEAD with it. OWNER-GATE.md: "Raising
-- the cap is a deliberate act, never a side effect of adding a migration file."
-- It is deliberate here, and it still excludes V13-V19 — which do not exist as
-- files yet and are S6's cutover work.
--
-- Writes money? No. Reversible? Yes — V20's seven rows are restorable from V20
-- itself, and nothing references a preset.
-- ============================================================================

SELECT 'V30: Vi amount presets...' AS Status;

-- DELETE-then-INSERT rather than a bare INSERT, so the end state is the same
-- whether VI currently has V20's seven rows, the three somebody added by hand
-- on the developer restore, or none at all after V28's wipe. A bare INSERT
-- would duplicate rows on two of those three.
--
-- ⚠ Scoped to VI by OPERATOR_CODE. V28 does an unscoped
-- `DELETE FROM rc_operator_preset` because it reseeds every operator; this one
-- must not, or it silently drops RJIO, ARTL and BSNL.
DELETE FROM rc_operator_preset WHERE OPERATOR_CODE = 'VI';

INSERT INTO rc_operator_preset (OPERATOR_CODE, AMOUNT, SORT_ORDER)
SELECT 'VI', p.AMOUNT, p.SORT_ORDER
FROM (
    SELECT  199.00 AS AMOUNT, 10 AS SORT_ORDER
    UNION ALL SELECT 239.00, 20
    UNION ALL SELECT 299.00, 30
) p
-- Guard, matching V20's: a preset on an operator that is not active is
-- unreachable config, and VI is only active because V20 merged IDEA and VDFN
-- into it (log §15.2).
WHERE EXISTS (
    SELECT 1 FROM rc_operator o
     WHERE o.OPERATOR_CODE = 'VI' AND o.IS_ACTIVE = 1
);

SELECT 'V30: done. Vi offers 199 / 239 / 299.' AS Status;
