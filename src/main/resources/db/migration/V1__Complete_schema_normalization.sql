-- ============================================================================
-- V1: Complete Schema Normalization and Data Migration (Production-Safe)
-- ============================================================================
-- Description: Normalize customer data adapting to actual production schema
-- Compatible with MySQL 8.0.21+
-- ============================================================================

SET SQL_SAFE_UPDATES = 0;
SET @start_time = NOW();

-- ============================================================================
-- PHASE 1: SCHEMA EXTENSIONS ON rs_cust_dtls
-- ============================================================================

SELECT 'Phase 1: Adding new columns to rs_cust_dtls...' AS Status;

-- Add FIRST_NAME
SET @column_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rs_cust_dtls' AND COLUMN_NAME = 'FIRST_NAME');
SET @sql = IF(@column_exists = 0, 'ALTER TABLE rs_cust_dtls ADD COLUMN FIRST_NAME VARCHAR(45) NULL AFTER cust_id', 'SELECT "FIRST_NAME exists" AS Info');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Add LAST_NAME
SET @column_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rs_cust_dtls' AND COLUMN_NAME = 'LAST_NAME');
SET @sql = IF(@column_exists = 0, 'ALTER TABLE rs_cust_dtls ADD COLUMN LAST_NAME VARCHAR(45) NULL AFTER FIRST_NAME', 'SELECT "LAST_NAME exists" AS Info');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Add USER_ID
SET @column_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rs_cust_dtls' AND COLUMN_NAME = 'USER_ID');
SET @sql = IF(@column_exists = 0, 'ALTER TABLE rs_cust_dtls ADD COLUMN USER_ID INT NULL', 'SELECT "USER_ID exists" AS Info');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Add EMAIL
SET @column_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rs_cust_dtls' AND COLUMN_NAME = 'EMAIL');
SET @sql = IF(@column_exists = 0, 'ALTER TABLE rs_cust_dtls ADD COLUMN EMAIL VARCHAR(100) NULL', 'SELECT "EMAIL exists" AS Info');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Add IS_ACTIVE
SET @column_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rs_cust_dtls' AND COLUMN_NAME = 'IS_ACTIVE');
SET @sql = IF(@column_exists = 0, 'ALTER TABLE rs_cust_dtls ADD COLUMN IS_ACTIVE BOOLEAN DEFAULT TRUE', 'SELECT "IS_ACTIVE exists" AS Info');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Add UPD_DTTM
SET @column_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rs_cust_dtls' AND COLUMN_NAME = 'UPD_DTTM');
SET @sql = IF(@column_exists = 0, 'ALTER TABLE rs_cust_dtls ADD COLUMN UPD_DTTM TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP', 'SELECT "UPD_DTTM exists" AS Info');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Add indexes
SET @index_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rs_cust_dtls' AND INDEX_NAME = 'idx_user_id');
SET @sql = IF(@index_exists = 0, 'ALTER TABLE rs_cust_dtls ADD INDEX idx_user_id (USER_ID)', 'SELECT "idx_user_id exists" AS Info');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Add foreign key
SET @fk_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rs_cust_dtls' AND CONSTRAINT_NAME = 'fk_cust_user');
SET @sql = IF(@fk_exists = 0, 'ALTER TABLE rs_cust_dtls ADD CONSTRAINT fk_cust_user FOREIGN KEY (USER_ID) REFERENCES rc_user(id) ON DELETE SET NULL', 'SELECT "fk_cust_user exists" AS Info');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT CONCAT('✓ Schema extensions completed at ', NOW()) AS Status;

-- ============================================================================
-- PHASE 2: MIGRATE CUSTOMER DATA FROM wt_purchase_party (IF user_id EXISTS)
-- ============================================================================

SELECT 'Phase 2: Migrating customer data from user relationships...' AS Status;

-- Check if wt_purchase_party has user_id column
SET @has_user_id = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wt_purchase_party' AND COLUMN_NAME = 'user_id');

-- If user_id exists, migrate names and link users to customers
SET @sql_migrate = IF(@has_user_id > 0,
  'UPDATE rs_cust_dtls c
   JOIN wt_purchase_party w ON c.cust_id = w.customer_id
   JOIN rc_user u ON w.user_id = u.id
   SET 
     c.FIRST_NAME = COALESCE(c.FIRST_NAME, u.FIRST_NAME),
     c.LAST_NAME = COALESCE(c.LAST_NAME, u.LAST_NAME),
     c.USER_ID = COALESCE(c.USER_ID, u.id),
     c.IS_ACTIVE = TRUE
   WHERE u.FIRST_NAME IS NOT NULL
     AND u.LAST_NAME IS NOT NULL',
  'SELECT "Skipping user_id migration - column not found in wt_purchase_party" AS Info'
);
PREPARE stmt_migrate FROM @sql_migrate;
EXECUTE stmt_migrate;
SET @rows_migrated = ROW_COUNT();
DEALLOCATE PREPARE stmt_migrate;

SELECT CONCAT('✓ Migrated ', @rows_migrated, ' customers from rc_user') AS Status;

-- ============================================================================
-- PHASE 3: MIGRATE FROM CUST_NAME (Split full names)
-- ============================================================================

SELECT 'Phase 3: Splitting CUST_NAME into FIRST_NAME and LAST_NAME...' AS Status;

-- Check if CUST_NAME column exists
SET @has_cust_name = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rs_cust_dtls' AND COLUMN_NAME = 'CUST_NAME');

SET @sql_split_names = IF(@has_cust_name > 0,
  'UPDATE rs_cust_dtls
   SET 
     FIRST_NAME = SUBSTRING_INDEX(CUST_NAME, '' '', 1),
     LAST_NAME = CASE 
       WHEN LOCATE('' '', CUST_NAME) > 0 
       THEN SUBSTRING(CUST_NAME, LOCATE('' '', CUST_NAME) + 1)
       ELSE ''.''
     END,
     IS_ACTIVE = TRUE
   WHERE FIRST_NAME IS NULL
     AND CUST_NAME IS NOT NULL
     AND CUST_NAME != ''''',
  'SELECT "Skipping CUST_NAME migration - column not found" AS Info'
);
PREPARE stmt_split FROM @sql_split_names;
EXECUTE stmt_split;
SET @rows_split = ROW_COUNT();
DEALLOCATE PREPARE stmt_split;

SELECT CONCAT('✓ Split ', @rows_split, ' customer names from CUST_NAME') AS Status;

-- ============================================================================
-- PHASE 4: BACKFILL CONTACT NUMBERS (IF contact_number EXISTS IN wt_purchase_party)
-- ============================================================================

SELECT 'Phase 4: Backfilling contact numbers from wt_purchase_party...' AS Status;

-- Check if wt_purchase_party has contact_number column
SET @has_contact_number = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wt_purchase_party' AND COLUMN_NAME = 'contact_number');

SET @sql_backfill = IF(@has_contact_number > 0,
  'UPDATE rs_cust_dtls c
   JOIN (
     SELECT customer_id, MAX(contact_number) AS contact_number
     FROM wt_purchase_party
     WHERE contact_number IS NOT NULL AND contact_number != ''''
     GROUP BY customer_id
   ) w ON w.customer_id = c.cust_id
   SET c.CONTACT_NUM = w.contact_number
   WHERE (c.CONTACT_NUM IS NULL OR c.CONTACT_NUM = '''')',
  'SELECT "Skipping contact number backfill - column not found in wt_purchase_party" AS Info'
);
PREPARE stmt_backfill FROM @sql_backfill;
EXECUTE stmt_backfill;
SET @rows_backfilled = ROW_COUNT();
DEALLOCATE PREPARE stmt_backfill;

SELECT CONCAT('✓ Backfilled ', @rows_backfilled, ' contact numbers') AS Status;

-- ============================================================================
-- PHASE 5: NORMALIZE wt_purchase_party (Remove user_id if exists)
-- ============================================================================

SELECT 'Phase 5: Normalizing wt_purchase_party (removing user_id)...' AS Status;

SET @column_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wt_purchase_party' AND COLUMN_NAME = 'user_id');
SET @sql = IF(@column_exists > 0, 'ALTER TABLE wt_purchase_party DROP COLUMN user_id', 'SELECT "user_id already removed from wt_purchase_party" AS Status');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT CONCAT('✓ Normalized wt_purchase_party at ', NOW()) AS Status;

-- ============================================================================
-- PHASE 6: VALIDATION & DIAGNOSTICS
-- ============================================================================

SELECT 'Phase 6: Running validation checks...' AS Status;

SELECT '======================== MIGRATION SUMMARY ========================' AS '';

SELECT 
  'Total customers' AS Metric,
  COUNT(*) AS Count
FROM rs_cust_dtls
UNION ALL
SELECT 
  'Customers with names migrated',
  COUNT(*)
FROM rs_cust_dtls
WHERE FIRST_NAME IS NOT NULL AND LAST_NAME IS NOT NULL
UNION ALL
SELECT 
  'Customers with user bindings',
  COUNT(*)
FROM rs_cust_dtls
WHERE USER_ID IS NOT NULL
UNION ALL
SELECT 
  'Customers without names (need review)',
  COUNT(*)
FROM rs_cust_dtls
WHERE FIRST_NAME IS NULL OR LAST_NAME IS NULL;

SELECT '====================================================================' AS '';

SET SQL_SAFE_UPDATES = 1;

SELECT CONCAT('✓✓✓ Migration completed successfully in ', 
  TIMESTAMPDIFF(SECOND, @start_time, NOW()), ' seconds') AS Status;
