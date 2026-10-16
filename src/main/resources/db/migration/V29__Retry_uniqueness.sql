-- ============================================================================
-- V29: One retry per failure, held by the database
-- ============================================================================
-- Spec: design-api.md §7 threat 3, §7.2, §5.3; design-domain.md §5.2.
--
-- An eighth prerequisite a document decided and no migration built — the same
-- pattern implementation-plan.md §0.6 records seven times for V28, and found
-- the same way: by building #12 against the schema rather than the documents.
--
-- §7's threat table, row 3, is explicit about both the mechanism and the reason
-- for choosing it over application code:
--
--     "Retry created twice | Worth it, and cheaper in the DB than in the app |
--      A unique key on retryOfRechargeId. The second attempt gets a constraint
--      violation -> 409. No key, no application-level lock"
--
-- and §7.2 then leans on it: #12 needs no Idempotency-Key header precisely
-- BECAUSE "it is guarded by the unique retry key".
--
-- V12 created the column and a plain, NON-UNIQUE index:
--
--     KEY idx_rech_retry (FAILED_RECHARGE_ID)
--
-- so neither guarantee existed. The second retry of one failure would have been
-- accepted by the database, and the two live recharges against a single item
-- line would then have had to be caught — if at all — by the §2.2(3) count in
-- RechargeFulfilmentService.apply, which runs AFTER both rows are written.
--
-- ⚠ THE APPLICATION ALREADY REFUSES THE SECOND RETRY, so this is defence in
-- depth rather than the only guard. RechargeFailureResolutionService takes
-- PESSIMISTIC_WRITE on the failed row and moves it to RETRIED, which is
-- terminal, so a concurrent second retry blocks and then fails the transition.
-- The key is still worth having: it is what makes the invariant true for every
-- future caller rather than for this one, and §7 chose it deliberately.
--
-- NULLs are unaffected. InnoDB permits unlimited NULLs in a unique index, so
-- the 52,517 rows V15 backfills with FAILED_RECHARGE_ID NULL — and every
-- ordinary non-retry recharge — remain legal.
--
-- ⚠ NUMBERING. design-ledger.md §8.1's opening-balance migrations have now been
-- pushed from V27/V28 to V28/V29 to V29/V30, and this moves them to V30/V31.
-- db/migration/README.md sanctions exactly that: they are S6 go-live work that
-- applies after everything here regardless, so "each shift costs nothing but a
-- renumber", and out-of-order=false leaves no gap below V28 to use instead.
--
-- SAFE ALONGSIDE THE LEGACY WRITER: rc_recharge is a V12 table that nothing
-- outside this module reads or writes, and it is empty until V15's backfill.
-- ============================================================================

SELECT 'V29: making one-retry-per-failure a database guarantee...' AS Status;

-- The unique index is added BEFORE the plain one is dropped. fk_rech_retry
-- needs an index on FAILED_RECHARGE_ID to exist at all times; dropping
-- idx_rech_retry first would leave the foreign key unsupported and MySQL would
-- refuse (errno 150). Adding first means uq_rech_retry is already there to
-- carry the constraint, and the drop is then just removing a duplicate.
ALTER TABLE rc_recharge
    ADD CONSTRAINT uq_rech_retry UNIQUE (FAILED_RECHARGE_ID);

ALTER TABLE rc_recharge
    DROP INDEX idx_rech_retry;

SELECT 'V29: done. A failure can now be retried at most once.' AS Status;
