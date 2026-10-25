-- ============================================================================
-- V32: Gate — verify the ticket booking schema
-- ============================================================================
-- Spec: design-ticket-booking.md §6.1's V32 row.
--
-- SCHEMA ASSERTIONS, NOT DATA ASSERTIONS, for the same reason V27 gives: there
-- is no booking data yet and there must not be. What this catches is the class
-- of damage no data gate can see — spring.jpa.hibernate.ddl-auto substituting
-- Hibernate's own DDL, which carries no foreign key, no CHECK and no index,
-- after which V31's CREATE TABLE IF NOT EXISTS guards SKIP the real thing. The
-- tables would appear to work while ck_tkt_funding — the one constraint holding
-- the flow-1/flow-2 distinction together — was simply absent.
--
-- IT REPORTS EVERY FAILURE AT ONCE rather than stopping at the first, so a
-- missing constraint and a mistyped column are one fix rather than two
-- migrate-fail cycles.
--
-- ⚠ THE IDIOM BELOW IS V27'S AND THE REASONS ARE V27'S, repeated because they
-- are not guessable:
--   * SIGNAL cannot run through PREPARE/EXECUTE (ERROR 1295), so the assertion
--     goes through a throwaway procedure. A gate written with the repo's
--     dynamic-SQL idiom migrates green and only reveals it cannot report on the
--     day it has something to report.
--   * MESSAGE_TEXT is capped at 128 characters and MySQL hard-errors above it
--     (ERROR 1648) rather than truncating, so the signal is LEFT(...) and the
--     full list is SELECTed first for whoever runs this by hand.
-- ============================================================================

SELECT 'V32: verifying the ticket booking schema...' AS Status;

SET @failures = '';

-- ============================================================================
-- 1. Both tables exist
-- ============================================================================
SET @missing = (
    SELECT GROUP_CONCAT(x.name ORDER BY x.name)
    FROM (
        SELECT 'ref_booking_mode' AS name
        UNION ALL SELECT 'tkt_booking'
    ) x
    WHERE NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.TABLES
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = x.name AND TABLE_TYPE = 'BASE TABLE'
    )
);
SET @failures = IF(@missing IS NULL, @failures, CONCAT(@failures, ' |TABLES:', @missing));

-- ============================================================================
-- 2. Collation, so the foreign keys can exist at all
-- ============================================================================
-- A bare CHARSET=utf8mb4 can resolve to utf8mb4_general_ci, and BOOKING_MODE
-- would then not foreign-key ref_booking_mode nor FUNDING_ACCOUNT_CODE
-- acc_account. Asserted because a table declared the sloppy way is otherwise
-- invisible until a FK silently was not created.
SET @missing = (
    SELECT GROUP_CONCAT(CONCAT(TABLE_NAME, '=', TABLE_COLLATION) ORDER BY TABLE_NAME)
    FROM INFORMATION_SCHEMA.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_TYPE = 'BASE TABLE'
      AND TABLE_NAME IN ('ref_booking_mode', 'tkt_booking')
      AND TABLE_COLLATION <> 'utf8mb4_0900_ai_ci'
);
SET @failures = IF(@missing IS NULL, @failures, CONCAT(@failures, ' |COLLATION:', @missing));

-- ============================================================================
-- 3. Every column, with its type and its nullability
-- ============================================================================
-- Nullability is the point, not decoration. FARE_AMOUNT and
-- FUNDING_ACCOUNT_CODE being NULLABLE is what expresses flow 2 at all; either
-- one declared NOT NULL makes flow 2 unrecordable, and COMMISSION_AMOUNT
-- declared NULLABLE makes a booking with no margin indistinguishable from one
-- whose margin nobody entered.
SET @missing = (
    SELECT GROUP_CONCAT(x.col ORDER BY x.col)
    FROM (
        SELECT 'BOOKING_ID' AS col, 'bigint' AS dtype, 'NO' AS nullable
        UNION ALL SELECT 'BOOKING_DTTM',         'datetime', 'NO'
        UNION ALL SELECT 'BOOKING_MODE',         'varchar',  'NO'
        UNION ALL SELECT 'PASSENGER_COUNT',      'smallint', 'NO'
        UNION ALL SELECT 'FARE_AMOUNT',          'decimal',  'YES'
        UNION ALL SELECT 'COMMISSION_AMOUNT',    'decimal',  'NO'
        UNION ALL SELECT 'FUNDING_ACCOUNT_CODE', 'varchar',  'YES'
        UNION ALL SELECT 'CUST_ID',              'int',      'YES'
        UNION ALL SELECT 'NOTE',                 'varchar',  'YES'
        UNION ALL SELECT 'CRE_BY',               'int',      'YES'
        UNION ALL SELECT 'CRE_DTTM',             'datetime', 'NO'
        UNION ALL SELECT 'UPD_DTTM',             'timestamp','NO'
    ) x
    WHERE NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS c
         WHERE c.TABLE_SCHEMA = DATABASE() AND c.TABLE_NAME = 'tkt_booking'
           AND c.COLUMN_NAME = x.col
           AND c.DATA_TYPE = x.dtype
           AND c.IS_NULLABLE = x.nullable
    )
);
SET @failures = IF(@missing IS NULL, @failures, CONCAT(@failures, ' |COLTYPE:', @missing));

-- BOOKING_ID is BIGINT and the entity field is therefore Long. Asserted as a
-- type and not just a name because the INT/Long mismatch S3 closed was invisible
-- at runtime — JDBC widens int to long — and only surfaced when ddl-auto=validate
-- was finally run from an IDE on the local profile.
SET @missing = (
    SELECT 'BOOKING_ID' FROM (SELECT 1) d
     WHERE NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tkt_booking'
           AND COLUMN_NAME = 'BOOKING_ID' AND EXTRA LIKE '%auto_increment%')
);
SET @failures = IF(@missing IS NULL, @failures, CONCAT(@failures, ' |AUTOINC:', @missing));

SET @missing = (
    SELECT GROUP_CONCAT(x.col ORDER BY x.col)
    FROM (
        SELECT 'CODE' AS col, 'varchar' AS dtype, 'NO' AS nullable
        UNION ALL SELECT 'DISPLAY_NAME', 'varchar', 'NO'
        UNION ALL SELECT 'SORT_ORDER',   'int',     'NO'
        UNION ALL SELECT 'IS_ACTIVE',    'tinyint', 'NO'
    ) x
    WHERE NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS c
         WHERE c.TABLE_SCHEMA = DATABASE() AND c.TABLE_NAME = 'ref_booking_mode'
           AND c.COLUMN_NAME = x.col
           AND c.DATA_TYPE = x.dtype
           AND c.IS_NULLABLE = x.nullable
    )
);
SET @failures = IF(@missing IS NULL, @failures, CONCAT(@failures, ' |MODECOL:', @missing));

-- ============================================================================
-- 4. Every foreign key, by name
-- ============================================================================
-- By NAME and not by count: Hibernate's substituted DDL would create none of
-- these, and a count could be satisfied by the wrong set.
SET @missing = (
    SELECT GROUP_CONCAT(x.name ORDER BY x.name)
    FROM (
        SELECT 'fk_tkt_mode' AS name
        UNION ALL SELECT 'fk_tkt_funding'
        UNION ALL SELECT 'fk_tkt_cust'
        UNION ALL SELECT 'fk_tkt_creby'
    ) x
    WHERE NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
         WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'tkt_booking'
           AND CONSTRAINT_NAME = x.name AND CONSTRAINT_TYPE = 'FOREIGN KEY'
    )
);
SET @failures = IF(@missing IS NULL, @failures, CONCAT(@failures, ' |FK:', @missing));

-- ============================================================================
-- 5. Every CHECK, by name — ck_tkt_funding above all
-- ============================================================================
-- ⚠ ck_tkt_funding is what lets the flow be read from the row instead of stored
-- as a flag that can contradict it. It must be ASSERTED here and not merely
-- written in V31: MySQL parses and silently ignores CHECK constraints before
-- 8.0.16, and a table created by anything other than V31 would carry none of
-- them.
SET @missing = (
    SELECT GROUP_CONCAT(x.name ORDER BY x.name)
    FROM (
        SELECT 'ck_tkt_pax' AS name
        UNION ALL SELECT 'ck_tkt_comm'
        UNION ALL SELECT 'ck_tkt_fare'
        UNION ALL SELECT 'ck_tkt_funding'
    ) x
    WHERE NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
         WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'tkt_booking'
           AND CONSTRAINT_NAME = x.name AND CONSTRAINT_TYPE = 'CHECK'
    )
);
SET @failures = IF(@missing IS NULL, @failures, CONCAT(@failures, ' |CHECK:', @missing));

-- ============================================================================
-- 6. Every index, by name
-- ============================================================================
SET @missing = (
    SELECT GROUP_CONCAT(x.name ORDER BY x.name)
    FROM (
        SELECT 'idx_tkt_date' AS name
        UNION ALL SELECT 'idx_tkt_mode'
        UNION ALL SELECT 'idx_tkt_funding'
        UNION ALL SELECT 'idx_tkt_cust'
    ) x
    WHERE NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tkt_booking'
           AND INDEX_NAME = x.name
    )
);
SET @failures = IF(@missing IS NULL, @failures, CONCAT(@failures, ' |INDEX:', @missing));

-- ============================================================================
-- 7. The chart of accounts the posting rules need
-- ============================================================================
-- The four funding accounts, the three receiving accounts and the income
-- account, all present and ACTIVE. V21 seeds every one of them, so this closes
-- the loop between design-ledger.md §11.6 and the chart rather than trusting
-- that the two were read together: a booking funded from an account that was
-- later deactivated would otherwise fail at posting time, in front of a
-- customer, instead of here.
SET @missing = (
    SELECT GROUP_CONCAT(x.code ORDER BY x.code)
    FROM (
        SELECT 'BANK_JANATA' AS code
        UNION ALL SELECT 'BANK_SELF'
        UNION ALL SELECT 'BANK_WIFE'
        UNION ALL SELECT 'BANK_FATHER'
        UNION ALL SELECT 'CASH_TICKET'
        UNION ALL SELECT 'UPI_CLEARING'
        UNION ALL SELECT 'AR_TICKET'
        UNION ALL SELECT 'INC_TKT_COMM'
    ) x
    WHERE NOT EXISTS (
        SELECT 1 FROM acc_account
         WHERE ACCOUNT_CODE = x.code AND IS_ACTIVE = TRUE
    )
);
SET @failures = IF(@missing IS NULL, @failures, CONCAT(@failures, ' |ACCOUNTS:', @missing));

-- AR_TICKET must REQUIRE a party. LedgerPostingService refuses a receivable leg
-- with no customer on it, and a receivable that reached no one's statement is
-- the whole reason that rule exists.
SET @missing = (
    SELECT 'AR_TICKET' FROM (SELECT 1) d
     WHERE NOT EXISTS (
        SELECT 1 FROM acc_account
         WHERE ACCOUNT_CODE = 'AR_TICKET' AND REQUIRES_PARTY = TRUE)
);
SET @failures = IF(@missing IS NULL, @failures, CONCAT(@failures, ' |ARPARTY:', @missing));

-- ============================================================================
-- 8. The voucher source V21 already seeded
-- ============================================================================
-- Asserted, NOT inserted. §6.1 asked V31 to add TICKET_BOOKING; V21:185 had
-- already done it, and re-adding it would be a duplicate-key failure. Checking
-- it here is what makes that correction safe rather than merely asserted in a
-- comment.
--
-- IS_AUTOMATIC, not IS_ACTIVE: this table has no IS_ACTIVE column. A booking's
-- voucher is written by the endpoint and never hand-entered, which is exactly
-- what IS_AUTOMATIC = TRUE records (V21:173-185).
SET @missing = (
    SELECT 'TICKET_BOOKING' FROM (SELECT 1) d
     WHERE NOT EXISTS (
        SELECT 1 FROM ref_voucher_source
         WHERE CODE = 'TICKET_BOOKING' AND IS_AUTOMATIC = TRUE)
);
SET @failures = IF(@missing IS NULL, @failures, CONCAT(@failures, ' |SOURCE:', @missing));

-- ============================================================================
-- 9. ref_booking_mode holds exactly the two modes, both active
-- ============================================================================
-- EXACTLY two, not at least two: BookingMode's matchesReferenceTable test
-- asserts the enum and this table agree, and a third row seeded by hand would
-- make the enum wrong without anything failing until a booking used it.
SET @missing = (
    SELECT CONCAT('expected 2 active, found ', COUNT(*))
    FROM ref_booking_mode WHERE IS_ACTIVE = TRUE
    HAVING COUNT(*) <> 2
);
SET @failures = IF(@missing IS NULL, @failures, CONCAT(@failures, ' |MODECOUNT:', @missing));

SET @missing = (
    SELECT GROUP_CONCAT(x.code ORDER BY x.code)
    FROM (
        SELECT 'RAIL' AS code, 'Railway' AS name, 10 AS sort_order
        UNION ALL SELECT 'BUS', 'Bus', 20
    ) x
    WHERE NOT EXISTS (
        SELECT 1 FROM ref_booking_mode
         WHERE CODE = x.code AND DISPLAY_NAME = x.name
           AND SORT_ORDER = x.sort_order AND IS_ACTIVE = TRUE
    )
);
SET @failures = IF(@missing IS NULL, @failures, CONCAT(@failures, ' |MODESEEDS:', @missing));

-- ============================================================================
-- 10. No bookings yet
-- ============================================================================
-- There is no path that writes one before the endpoints exist, and no backfill
-- (T7). A row here means something wrote to this table that should not have.
SET @missing = (
    SELECT CONCAT('tkt_booking has ', COUNT(*), ' rows')
    FROM tkt_booking HAVING COUNT(*) > 0
);
SET @failures = IF(@missing IS NULL, @failures, CONCAT(@failures, ' |NOTEMPTY:', @missing));

-- ============================================================================
-- 11. Signal, once, with everything
-- ============================================================================
DROP PROCEDURE IF EXISTS v32_assert;

DELIMITER $$
CREATE PROCEDURE v32_assert(IN p_failures TEXT)
BEGIN
    IF p_failures <> '' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = p_failures;
    END IF;
END$$
DELIMITER ;

SELECT IF(@failures = '', 'V32: all ticket schema assertions passed',
          CONCAT('V32 Gate FAILED:', @failures)) AS gate_result;

CALL v32_assert(IF(@failures = '', '', LEFT(CONCAT('V32 Gate FAILED:', @failures), 128)));
DROP PROCEDURE v32_assert;

SELECT 'V32: complete.' AS Status;
