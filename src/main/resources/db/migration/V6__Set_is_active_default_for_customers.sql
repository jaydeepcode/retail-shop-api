-- ============================================================================
-- V6: Set is_active Default for All Customers
-- ============================================================================
-- Description: Update all existing wt_purchase_party records to have 
--              is_active = true and ensure the column has DEFAULT TRUE
--              for future records. This ensures all approved customers
--              appear in the approved customers list.
-- Compatible with MySQL 8.0.21+
-- ============================================================================

SET SQL_SAFE_UPDATES = 0;

SELECT 'V6: Starting is_active default migration...' AS Status;

-- ============================================================================
-- PHASE 1: Check if is_active column exists in wt_purchase_party
-- ============================================================================

SELECT 'Phase 1: Checking is_active column in wt_purchase_party...' AS Status;

SET @column_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wt_purchase_party'
      AND COLUMN_NAME = 'is_active'
);

-- ============================================================================
-- PHASE 2: Add is_active column if it doesn't exist
-- ============================================================================

SET @sql = IF(
    @column_exists = 0,
    'ALTER TABLE wt_purchase_party ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE',
    'SELECT ''is_active column already exists, proceeding to update...'' AS Info'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SELECT 'Phase 2: Column check/add completed' AS Status;

-- ============================================================================
-- PHASE 3: Update all existing records to set is_active = true
-- ============================================================================

SELECT 'Phase 3: Updating all existing records to is_active = true...' AS Status;

-- Update all records where is_active is NULL or false
UPDATE wt_purchase_party
SET is_active = TRUE
WHERE is_active IS NULL OR is_active = FALSE;

SET @rows_updated = ROW_COUNT();
SELECT CONCAT('✓ Updated ', @rows_updated, ' records to is_active = true') AS Status;

-- ============================================================================
-- PHASE 4: Ensure column has DEFAULT TRUE and NOT NULL constraints
-- ============================================================================

SELECT 'Phase 4: Ensuring is_active column has DEFAULT TRUE and NOT NULL...' AS Status;

-- Modify the column to ensure it has DEFAULT TRUE and NOT NULL
-- This handles cases where the column might exist but not have the correct default
SET @sql = 'ALTER TABLE wt_purchase_party MODIFY COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE';
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SELECT '✓ is_active column now has DEFAULT TRUE and NOT NULL constraints' AS Status;

SET SQL_SAFE_UPDATES = 1;

SELECT 'V6: is_active default migration completed successfully.' AS Status;

