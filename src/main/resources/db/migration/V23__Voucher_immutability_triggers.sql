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
-- WHY TRIGGERS AT ALL, GIVEN §2.4 OFFERS A FALLBACK.
-- The fallback is to drop this file and rely on the row-level CHECKs, the single
-- posting choke-point and a daily drift query. What that loses: a hand-written
-- UPDATE or INSERT in a MySQL client can produce an unbalanced or
-- retroactively-altered voucher, and you find out the next time the check runs
-- rather than at the moment it happens. FRD §8.5's whole argument is that an
-- auditor cannot be pointed at THE balance if two versions exist, so §2.4
-- recommends keeping them: forty lines of SQL is what separates real
-- double-entry from double-entry by convention.
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

SELECT 'V23: creating the voucher immutability triggers...' AS Status;

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
-- 3. Lines are never updated, by anyone, ever.
-- ----------------------------------------------------------------------------
-- Unconditional. acc_voucher_line is the record of what was posted; historical
-- P&L is a SUM over it, so an immutable line is what guarantees a reported
-- figure cannot move (§4.1 mechanism 2).
CREATE TRIGGER trg_acc_voucher_line_bu BEFORE UPDATE ON acc_voucher_line
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'acc_voucher_line is append-only';
END$$

-- ----------------------------------------------------------------------------
-- 4. A line may only be deleted while its account is not reconciled past the
--    voucher's date.
-- ----------------------------------------------------------------------------
-- The parent is always still present here: fk_line_voucher declares no
-- ON DELETE action, so MySQL requires the lines to go before the voucher.
CREATE TRIGGER trg_acc_voucher_line_bd BEFORE DELETE ON acc_voucher_line
FOR EACH ROW
BEGIN
    DECLARE v_sealed DATETIME;
    DECLARE v_date   DATE;
    DECLARE v_locked INT;

    SELECT SEALED_AT, VOUCHER_DATE INTO v_sealed, v_date
      FROM acc_voucher WHERE VOUCHER_ID = OLD.VOUCHER_ID;

    SELECT COUNT(*) INTO v_locked
      FROM acc_reconciliation r
     WHERE r.ACCOUNT_CODE = OLD.ACCOUNT_CODE
       AND r.RECON_DATE  >= v_date;

    IF v_sealed IS NOT NULL AND v_locked > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'account is reconciled past this date; correct with a JOURNAL (FRD 8.3)';
    END IF;
END$$

-- ----------------------------------------------------------------------------
-- 5. The opening balance voucher is never deletable at all.
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
