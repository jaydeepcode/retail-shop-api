-- ============================================================================
-- V23: Voucher Immutability Triggers
-- ============================================================================
-- Spec: design-ledger.md §2.4, and §8.1's V22 row.
--
-- ⚠ THIS IS THE FIRST DELIMITER-BASED MIGRATION IN THIS REPOSITORY'S HISTORY.
-- That is exactly why the design puts the triggers in their own file (§8.1's V22
-- row says so explicitly): if Flyway's MySQL parser mishandles DELIMITER, the
-- failure must not take out V22's table DDL. flyway-mysql is a declared
-- dependency of both the application and the Maven plugin (pom.xml), and it is
-- the component that understands DELIMITER — flyway-core alone does not.
--
-- SAFE ALONGSIDE THE LEGACY WRITER: every trigger is on an acc_* table. The
-- legacy application cannot reach them.
--
-- ⚠⚠ DEPLOYMENT PREREQUISITE — THIS MIGRATION NEEDS A PRIVILEGE THE OTHERS DO NOT.
-- Found by running it, not by reading it. MySQL 8.0.43 ships with
-- log_bin = ON and log_bin_trust_function_creators = OFF, and in that state
-- CREATE TRIGGER by a user WITHOUT the SUPER privilege fails outright:
--
--   ERROR 1419 (HY000): You do not have the SUPER privilege and binary logging
--   is enabled (you *might* want to use the less safe
--   log_bin_trust_function_creators variable)
--
-- Measured on a clean mysql:8.0.43 against a user holding
-- GRANT ALL PRIVILEGES ON recharge.* (so TRIGGER is granted; SUPER is not):
--   log_bin=1, trust=0, no SUPER   -> ERROR 1419
--   log_bin=1, trust=1, no SUPER   -> succeeds
--   log_bin=1, trust=0, with SUPER -> succeeds
--
-- So BEFORE the first deploy carrying V23, one of these must be true of the
-- account in spring.datasource.username:
--
--   (a) the server has log_bin_trust_function_creators = 1   <- preferred
--       SET GLOBAL log_bin_trust_function_creators = 1;  (and put it in my.cnf,
--       or it is lost on restart). This is the least-privilege route and is what
--       LedgerMigrationTest exercises, via --log-bin-trust-function-creators=1
--       on its container.
--   (b) the account holds SUPER. Do not choose this for an application account
--       merely to run one migration.
--
-- Nothing in design-ledger.md, implementation-plan.md or db/migration/README.md
-- mentioned this, and V20-V22 do not need it — only this file, V24 and V25 do,
-- because they are the three that create triggers. The failure mode is a
-- MIGRATION THAT STOPS HALFWAY: V21 and V22's tables land, V23 fails, and the
-- application will not start. That is recoverable (fix the privilege and
-- re-migrate; this file is re-runnable), but it should not be discovered on a
-- go-live morning.
--
-- WHY ONLY THREE TRIGGERS, WHEN §2.4 SPECIFIES FIVE.
-- Reduced from five to three on 2026-10-09, after the owner challenged how much
-- business logic belongs in the database at all. The distinction that settled it:
--
--   * "a voucher's legs must sum to zero" and "a rate cannot be closed in the
--     past" are ACCOUNTING POLICY. They belong in the service, where they can
--     return a sentence instead of SIGNAL SQLSTATE '45000'.
--   * "a posted money row is never updated" is a STORAGE PROPERTY, no more
--     business logic than NOT NULL or a unique index.
--
-- And the storage property has a better mechanism than a trigger. A per-table
-- GRANT that simply never grants UPDATE gives append-only with no procedural
-- code in the database and no privilege requirement at all -- see the grants
-- section of db/migration/README.md. So:
--
--   trg_acc_voucher_line_bu   (lines append-only)        -> a GRANT
--   trg_acc_float_movement_bu (movements append-only)    -> a GRANT  [was V24]
--   trg_acc_voucher_line_bd   (reconciliation delete lock) -> the service
--   trg_acc_commission_rate_bu (rate immutability)       -> the service  [was V25]
--
-- WHAT THE THREE THAT REMAIN BUY, AND WHY A GRANT CANNOT REPLACE THEM.
-- They are the only enforcement of the CROSS-ROW invariant. With grants alone,
-- hand-SQL can still do this and nothing refuses it:
--
--   INSERT INTO acc_voucher (..., SEALED_AT) VALUES (..., NOW());  -- sealed on arrival
--   INSERT INTO acc_voucher_line ... DR_AMOUNT 100.00;
--   INSERT INTO acc_voucher_line ... CR_AMOUNT  99.00;             -- unbalanced, sealed
--
-- Trigger 2 refuses the second and third statements, so the only route to a
-- sealed voucher is insert-unsealed -> add legs -> seal, and trigger 1 sums the
-- children at that moment. That is the whole mechanism; drop trigger 2 and
-- trigger 1 becomes bypassable.
--
-- Two things make this non-paranoid in THIS database specifically. Hand-SQL is a
-- documented operating mode, not a hypothesis -- requirement 10 says operator
-- config "is edited in tables with no screen, i.e. by hand in SQL", and V10's
-- header says the same. And this schema has already produced two disagreeing
-- copies of the same money fact: §1.2 measured balance_amount drifted Rs 120
-- from its own table's facts, and BNW 2026 disagrees with water deposits by
-- Rs 70. FRD §8.5 exists because of that history.
--
-- The service still validates the balance itself and returns a readable error.
-- This is defence in depth for writers that are not the application, which is
-- the only reason it is here.
--
-- WHAT FRD §8.3's CORRECTION REGIME ACTUALLY IS, expressed by triggers 1 and 4:
--   * BEFORE the reconciliation point — no acc_reconciliation row covers the
--     account for that date, so the whole voucher can be deleted and re-posted.
--     From the user's point of view that is "a direct edit". The lines are still
--     never mutated, so there is never a half-edited money row.
--   * AFTER reconciliation, or for a past day — the delete is refused and the
--     only route is a new JOURNAL with REVERSES_VOUCHER_ID pointing at the
--     original. The original stays byte-identical.
--
-- DROP ... IF EXISTS before each CREATE, so a half-applied run can be re-executed
-- by hand. MySQL DDL is not transactional, which makes that a real situation.
-- CREATE TRIGGER IF NOT EXISTS would be shorter but needs 8.0.29+, and
-- design-ledger.md states the floor as 8.0.21.
-- ============================================================================

SELECT 'V23: creating the three voucher integrity triggers...' AS Status;

-- The two names this migration no longer creates are dropped anyway, so a
-- database that ran an earlier build of this file converges on the new shape.
DROP TRIGGER IF EXISTS trg_acc_voucher_bu;
DROP TRIGGER IF EXISTS trg_acc_voucher_line_bi;
DROP TRIGGER IF EXISTS trg_acc_voucher_line_bu;
DROP TRIGGER IF EXISTS trg_acc_voucher_line_bd;
DROP TRIGGER IF EXISTS trg_acc_voucher_bd;

DELIMITER $$

-- ----------------------------------------------------------------------------
-- 1. A voucher's legs must balance at the moment it is sealed, and it may never
--    be un-sealed or otherwise altered afterwards.
-- ----------------------------------------------------------------------------
-- This is the one that makes the zero-balance invariant real. A CHECK cannot do
-- it (it would reject the first leg of a two-leg voucher) and neither can a
-- row-level trigger on acc_voucher_line. A trigger on the PARENT can, because it
-- can run an aggregate over the children.
CREATE TRIGGER trg_acc_voucher_bu BEFORE UPDATE ON acc_voucher
FOR EACH ROW
BEGIN
    DECLARE v_dr DECIMAL(38,2);
    DECLARE v_cr DECIMAL(38,2);
    DECLARE v_n  INT;

    IF OLD.SEALED_AT IS NOT NULL THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'acc_voucher is immutable once sealed; post a correcting JOURNAL instead';
    END IF;

    IF NEW.SEALED_AT IS NOT NULL THEN
        SELECT COALESCE(SUM(DR_AMOUNT),0), COALESCE(SUM(CR_AMOUNT),0), COUNT(*)
          INTO v_dr, v_cr, v_n
          FROM acc_voucher_line WHERE VOUCHER_ID = NEW.VOUCHER_ID;

        IF v_n < 2 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'a voucher needs at least two legs';
        END IF;
        IF v_dr <> v_cr THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'voucher does not balance: SUM(DR) <> SUM(CR)';
        END IF;
    END IF;
END$$

-- ----------------------------------------------------------------------------
-- 2. Lines may only be added to an unsealed voucher.
-- ----------------------------------------------------------------------------
-- Without this, trigger 1's seal-time check could be defeated by adding a leg
-- afterwards.
CREATE TRIGGER trg_acc_voucher_line_bi BEFORE INSERT ON acc_voucher_line
FOR EACH ROW
BEGIN
    IF (SELECT SEALED_AT FROM acc_voucher WHERE VOUCHER_ID = NEW.VOUCHER_ID) IS NOT NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cannot add a leg to a sealed voucher';
    END IF;
END$$

-- ----------------------------------------------------------------------------
-- 3. REMOVED -- lines append-only is now a GRANT.
-- ----------------------------------------------------------------------------
-- Was trg_acc_voucher_line_bu, an unconditional SIGNAL on UPDATE. A per-table
-- grant that never grants UPDATE on acc_voucher_line achieves the same thing
-- with no trigger and no SUPER privilege, and applies to every connection.
-- Verified: UPDATE is then refused with ERROR 1142 while INSERT, SELECT and
-- DELETE all still work, and the header's seal UPDATE is unaffected.
-- See db/migration/README.md. NOTE this makes the property a DEPLOYMENT step
-- rather than a schema guarantee -- the honest cost of the change.

-- ----------------------------------------------------------------------------
-- 4. REMOVED -- the reconciliation delete lock is now a service rule.
-- ----------------------------------------------------------------------------
-- Was trg_acc_voucher_line_bd. It is FRD §8.3 policy, not a storage property:
-- "once an account is reconciled past this date, correct with a JOURNAL". That
-- reads far better as a 422 with a sentence than as SIGNAL SQLSTATE '45000'.
-- It lives in LedgerCorrectionRules.assertVoucherDeletable, and S5's void and
-- correction endpoints are what call it. S2 ships no delete path at all, so
-- nothing can reach it through the application yet.

-- ----------------------------------------------------------------------------
-- 3. The opening balance voucher is never deletable at all.
-- ----------------------------------------------------------------------------
-- It is the foundation every subsequent balance stands on, and unlike an
-- ordinary voucher there is nothing to re-post it from (§7: pre-D history is not
-- migrated, only opening balances).
CREATE TRIGGER trg_acc_voucher_bd BEFORE DELETE ON acc_voucher
FOR EACH ROW
BEGIN
    IF OLD.SOURCE_TYPE = 'OPENING' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'the opening balance voucher cannot be deleted';
    END IF;
END$$

DELIMITER ;

SELECT 'V23: complete.' AS Status;
