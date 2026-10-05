-- ============================================================================
-- V4: Cleanup legacy columns after lookup migration
-- ============================================================================
-- Drops redundant columns that are now superseded by lookup-backed fields.
-- Execute only after the application has been updated to rely on the new
-- *_CODE columns.
-- ============================================================================

SET SQL_SAFE_UPDATES = 0;

SELECT 'V4: Starting legacy column cleanup...' AS Status;

-- --------------------------------------------------------------------------
-- rs_cust_dtls.IS_ACTIVE (replaced by STATUS_CODE)
-- --------------------------------------------------------------------------
SET @column_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'rs_cust_dtls'
      AND COLUMN_NAME = 'IS_ACTIVE'
);

SET @sql = IF(
    @column_exists > 0,
    'ALTER TABLE rs_cust_dtls DROP COLUMN IS_ACTIVE',
    'SELECT ''Column IS_ACTIVE already removed'' AS Info'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- --------------------------------------------------------------------------
-- wt_purchase_party.storage_type (replaced by STORAGE_TYPE_CODE)
-- --------------------------------------------------------------------------
SET @column_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wt_purchase_party'
      AND COLUMN_NAME = 'storage_type'
);

SET @sql = IF(
    @column_exists > 0,
    'ALTER TABLE wt_purchase_party DROP COLUMN storage_type',
    'SELECT ''Column storage_type already removed'' AS Info'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- --------------------------------------------------------------------------
-- wt_purchase_details.pump_used / status (replaced by *_CODE columns)
-- --------------------------------------------------------------------------
SET @column_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wt_purchase_details'
      AND COLUMN_NAME = 'pump_used'
);

SET @sql = IF(
    @column_exists > 0,
    'ALTER TABLE wt_purchase_details DROP COLUMN pump_used',
    'SELECT ''Column pump_used already removed'' AS Info'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wt_purchase_details'
      AND COLUMN_NAME = 'status'
);

SET @sql = IF(
    @column_exists > 0,
    'ALTER TABLE wt_purchase_details DROP COLUMN status',
    'SELECT ''Column status already removed'' AS Info'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET SQL_SAFE_UPDATES = 1;

SELECT 'V4: Legacy column cleanup complete.' AS Status;




