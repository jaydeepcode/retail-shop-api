-- ============================================================================
-- V2: Cleanup Deprecated Columns (AFTER VALIDATION ONLY)
-- ============================================================================
-- WARNING: Only run this after thoroughly testing the application
-- ============================================================================

SET SQL_SAFE_UPDATES = 0;

SELECT 'WARNING: This will permanently remove deprecated columns!' AS Alert;
SELECT 'Press Ctrl+C within 5 seconds to cancel...' AS Alert;
SELECT SLEEP(5);

-- Remove deprecated columns from rs_cust_dtls (works on all MySQL versions)
SET @column_exists_cust_name = (
  SELECT COUNT(*) 
  FROM INFORMATION_SCHEMA.COLUMNS 
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'rs_cust_dtls' 
    AND COLUMN_NAME = 'CUST_NAME'
);

SET @sql = IF(@column_exists_cust_name > 0, 
  'ALTER TABLE rs_cust_dtls DROP COLUMN CUST_NAME', 
  'SELECT "Column CUST_NAME already removed" AS Status');

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Remove deprecated columns from rc_user
SET @column_exists_first_name = (
  SELECT COUNT(*) 
  FROM INFORMATION_SCHEMA.COLUMNS 
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'rc_user' 
    AND COLUMN_NAME = 'FIRST_NAME'
);

SET @sql2 = IF(@column_exists_first_name > 0, 
  'ALTER TABLE rc_user DROP COLUMN FIRST_NAME, DROP COLUMN LAST_NAME', 
  'SELECT "Columns already removed from rc_user" AS Status');

PREPARE stmt2 FROM @sql2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;

SET SQL_SAFE_UPDATES = 1;

SELECT '✓ Cleanup completed. Deprecated columns removed.' AS Status;


