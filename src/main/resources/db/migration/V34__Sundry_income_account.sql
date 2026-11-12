-- ============================================================================
-- V34: INC_SUNDRY_SHOP — the income account a variance can legally name
-- ============================================================================
-- ⚠ THE CAP MOVES WITH THIS FILE. spring.flyway.target went 33 -> 34 in
-- application.properties and application-container.properties, with the owner's
-- decision of 2026-11-02 (next-session-s5.md, "the income-account gap").
-- Recorded here rather than in a commit message alone, per OWNER-GATE.md:
-- raising the cap is a deliberate act and never a side effect of adding a file.
--
-- WHAT IT OVERTURNS AND WHY THAT IS SAFE. next-session-s5.md opened by saying
-- S5 "must not write a migration -- everything is seeded". That was wrong on
-- "everything is seeded" and the brief carries the correction. The cap's stated
-- purpose is keeping V13-V19 out -- V14 alone adds CHECK (amt_returned >= 0),
-- which breaks the legacy app's next UPI write. V13-V19 are BELOW this version
-- and still do not exist as files, so 34 cannot admit them. The other thing the
-- cap does, stopping a new file from applying by accident, is unchanged: 34 is
-- as deliberate as 33 was. S5 writes this migration and no other; the opening
-- balances move to V35/V36.
--
-- WHY THE ACCOUNT EXISTS AT ALL. design-ledger-api.md §1.1 requires the account
-- named in a day close's `explained` block to be, when the direction is IN, "an
-- INCOME account with ALLOW_MANUAL = TRUE". V21 seeds five income accounts and
-- ALL FIVE carry ALLOW_MANUAL = FALSE -- INC_WATER_TRIP, INC_RCHG_COMM,
-- INC_RCHG_SURCHARGE, INC_TKT_COMM, INC_CASH_OVER. The expense side has five
-- with TRUE, which is why `direction: OUT` has always been buildable and IN has
-- not. So `direction: IN` had no account it could legally name, and the log
-- §14.4 correction that added it was specified in three documents without ever
-- being implementable. Two of artboard 9's four variance buttons are that
-- direction:
--
--   * small change rounded away, never handed over -- Rs.1 change occurs 12,026
--     times in history, roughly Rs.1,000 a year (log §14.5);
--   * someone paid for a photocopy, a form, a printout -- 1,179 baskets over
--     seven years, 21 in FY2026-27 (log §14.3).
--
-- Both post here, with which of the two it was carried in the narration. If
-- photocopy income is ever wanted reported apart from rounding, that is a
-- second account later, not a restructure.
--
-- ⚠ INC_CASH_OVER WAS CONSIDERED AND REJECTED (owner, 2026-11-02), and reusing
-- it would have needed NO migration at all -- A7 keeps ALLOW_MANUAL = FALSE
-- accounts out of the GENERIC voucher endpoints only, and the close already
-- posts to it at step 4. So this was a judgement about meaning, not about cost.
-- INC_CASH_OVER holds exactly one thing: the drawer had more and nobody knows
-- why. It is the only figure that answers "how much can we not account for?"
-- and it should sit near zero. Mixing Rs.1,000 a year of rounding into it would
-- leave a genuine unexplained Rs.400 invisible, and would implement the
-- opposite of log §9.1's "a known cause posts as the real thing rather than
-- into the variance account" while looking like compliance. It degrades rather
-- than breaks, which is the worse failure.
--
-- ⚠ WHICH IS WHY INC_CASH_OVER'S ALLOW_MANUAL IS NOT FLIPPED HERE, and why
-- SundryIncomeMigrationTest asserts it is still FALSE. Flipping it is the
-- one-line "fix" that makes the 422 go away and quietly destroys the only
-- unexplained-cash figure the design has.
--
-- THE MIRROR. Deliberately the counterpart of V21's EXP_SUNDRY_SHOP ('Sundry
-- Expenses - Shop', EXPENSE, SHARED, ALLOW_MANUAL = TRUE, SORT_ORDER 640), so
-- the two sundry accounts read as a pair in the picker and on the P&L.
-- BUSINESS_CODE is SHARED because neither reason belongs to one business: the
-- rounding arises wherever change is given, and a photocopy is not a recharge,
-- a ticket or a water trip.
--
-- SORT_ORDER 540 places it inside the income block (500 INC_WATER_TRIP, 510
-- INC_RCHG_COMM, 515 INC_RCHG_SURCHARGE, 520 INC_TKT_COMM, 590 INC_CASH_OVER)
-- in the same relative position EXP_SUNDRY_SHOP holds on the expense side:
-- last of the ordinary accounts, immediately before the reconciliation
-- variance one. Nothing reads SORT_ORDER but the picker.
--
-- ⚠ APPEND-ONLY from the moment this is applied anywhere. A change of mind is
-- V35, never an edit to this file -- Flyway validates checksums before it
-- migrates, so an edit stops the application from starting at all.
-- ============================================================================

SELECT 'V34: seeding INC_SUNDRY_SHOP...' AS Status;

-- WHERE NOT EXISTS rather than INSERT IGNORE, following V21's own seeding
-- pattern: IGNORE would also swallow a genuine failure, such as a violated
-- foreign key onto ref_account_type or ref_business, and report success.
--
-- ⚠ EVERY COLUMN CARRIES AN `AS` ALIAS AND THAT IS NOT DECORATION. MySQL names
-- a derived table's literal column after its VALUE, and 'INCOME' appears twice
-- here -- as ACCOUNT_TYPE and as REPORT_GROUP -- so an unaliased version of
-- this statement fails with "Duplicate column name 'INCOME'". Found by writing
-- the re-run assertion in SundryIncomeMigrationTest without them.
--
-- IS_RECONCILABLE = FALSE: an income account is never counted. ALLOW_MANUAL =
-- TRUE is the whole point of the file. REQUIRES_PARTY = FALSE: there is no
-- customer dimension on a rounding difference. CASH_POOL, OPERATOR_CODE and
-- FLOAT_PATTERN stay NULL, which ck_acct_float requires of any non-float
-- account carrying no operator.
INSERT INTO acc_account (ACCOUNT_CODE, DISPLAY_NAME, ACCOUNT_TYPE, BUSINESS_CODE, REPORT_GROUP,
                         IS_RECONCILABLE, ALLOW_MANUAL, REQUIRES_PARTY, SORT_ORDER)
SELECT src.* FROM (
  SELECT 'INC_SUNDRY_SHOP' AS ACCOUNT_CODE, 'Sundry Income - Shop' AS DISPLAY_NAME,
         'INCOME' AS ACCOUNT_TYPE, 'SHARED' AS BUSINESS_CODE, 'INCOME' AS REPORT_GROUP,
         FALSE AS IS_RECONCILABLE, TRUE AS ALLOW_MANUAL, FALSE AS REQUIRES_PARTY,
         540 AS SORT_ORDER
) AS src
WHERE NOT EXISTS (SELECT 1 FROM acc_account a WHERE a.ACCOUNT_CODE = 'INC_SUNDRY_SHOP');

-- The gate V21's own seeds do not get: this file exists for ONE row, so if the
-- row is not there afterwards the migration has failed whatever Flyway says.
-- The condition is deliberately the §1.1 RULE rather than "the code exists" --
-- an INCOME account, manual-postable, active -- because that, and not the
-- spelling of the code, is what endpoint 2 looks for.
SET @ok = (
    SELECT COUNT(*) FROM acc_account
    WHERE ACCOUNT_CODE = 'INC_SUNDRY_SHOP'
      AND ACCOUNT_TYPE = 'INCOME' AND ALLOW_MANUAL = TRUE AND IS_ACTIVE = TRUE
);
-- MESSAGE_TEXT is capped at 128 characters and MySQL hard-errors above it, per
-- V32's header.
SET @msg = IF(@ok = 1, '',
    'V34 FAILED: INC_SUNDRY_SHOP is not an active INCOME account with ALLOW_MANUAL');

-- The DELIMITER wrapper is V27's pattern, not decoration: without it Flyway's
-- parser ends the statement at the first semicolon inside the body.
DROP PROCEDURE IF EXISTS v34_assert;

DELIMITER $$
CREATE PROCEDURE v34_assert(IN p_failure TEXT)
BEGIN
    IF p_failure <> '' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = p_failure;
    END IF;
END$$
DELIMITER ;

CALL v34_assert(@msg);
DROP PROCEDURE v34_assert;

SELECT 'V34: complete.' AS Status;
