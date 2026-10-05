-- ============================================================================
-- V3: Introduce Reference Tables and Normalize Status Columns
-- ============================================================================
-- Creates lookup tables, aligns core entities with reference codes, and
-- backfills existing data. Designed to be idempotent for safe re-runs.
-- ============================================================================

SET SQL_SAFE_UPDATES = 0;

SELECT 'V3: Starting reference data normalization...' AS Status;

-- ============================================================================
-- Create reference tables (if missing)
-- ============================================================================

SET @tbl_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ref_account_status'
);
SET @sql = IF(
    @tbl_exists = 0,
    'CREATE TABLE ref_account_status (
        CODE VARCHAR(20) PRIMARY KEY,
        DESCRIPTION VARCHAR(100) NOT NULL,
        IS_TERMINAL BOOLEAN NOT NULL DEFAULT FALSE,
        CRE_DTTM TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci',
    'SELECT ''ref_account_status exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @tbl_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ref_customer_status'
);
SET @sql = IF(
    @tbl_exists = 0,
    'CREATE TABLE ref_customer_status (
        CODE VARCHAR(20) PRIMARY KEY,
        DESCRIPTION VARCHAR(100) NOT NULL,
        CRE_DTTM TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci',
    'SELECT ''ref_customer_status exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @tbl_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ref_customer_role'
);
SET @sql = IF(
    @tbl_exists = 0,
    'CREATE TABLE ref_customer_role (
        CODE VARCHAR(20) PRIMARY KEY,
        DESCRIPTION VARCHAR(100) NOT NULL,
        CRE_DTTM TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci',
    'SELECT ''ref_customer_role exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @tbl_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ref_storage_type'
);
SET @sql = IF(
    @tbl_exists = 0,
    'CREATE TABLE ref_storage_type (
        CODE VARCHAR(30) PRIMARY KEY,
        DESCRIPTION VARCHAR(100) NOT NULL,
        CRE_DTTM TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci',
    'SELECT ''ref_storage_type exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @tbl_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ref_pump_type'
);
SET @sql = IF(
    @tbl_exists = 0,
    'CREATE TABLE ref_pump_type (
        CODE VARCHAR(30) PRIMARY KEY,
        DESCRIPTION VARCHAR(100) NOT NULL,
        CRE_DTTM TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci',
    'SELECT ''ref_pump_type exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @tbl_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ref_trip_status'
);
SET @sql = IF(
    @tbl_exists = 0,
    'CREATE TABLE ref_trip_status (
        CODE VARCHAR(30) PRIMARY KEY,
        DESCRIPTION VARCHAR(100) NOT NULL,
        IS_TERMINAL BOOLEAN NOT NULL DEFAULT FALSE,
        CRE_DTTM TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci',
    'SELECT ''ref_trip_status exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================================
-- Seed reference data
-- ============================================================================

INSERT INTO ref_account_status (CODE, DESCRIPTION, IS_TERMINAL)
SELECT src.CODE, src.DESCRIPTION, src.IS_TERMINAL
FROM (
    SELECT 'PENDING' AS CODE, 'Awaiting approval' AS DESCRIPTION, FALSE AS IS_TERMINAL
    UNION ALL SELECT 'APPROVED', 'Approved but not yet active', FALSE
    UNION ALL SELECT 'ACTIVE', 'Active account', FALSE
    UNION ALL SELECT 'SUSPENDED', 'Temporarily disabled', FALSE
    UNION ALL SELECT 'DISABLED', 'Permanently disabled', TRUE
) AS src
WHERE NOT EXISTS (
    SELECT 1 FROM ref_account_status tgt WHERE tgt.CODE = src.CODE
);

INSERT INTO ref_customer_status (CODE, DESCRIPTION)
SELECT src.CODE, src.DESCRIPTION
FROM (
    SELECT 'ACTIVE' AS CODE, 'Customer is active' AS DESCRIPTION
    UNION ALL SELECT 'INACTIVE', 'Customer is inactive'
    UNION ALL SELECT 'PENDING', 'Pending verification'
) AS src
WHERE NOT EXISTS (
    SELECT 1 FROM ref_customer_status tgt WHERE tgt.CODE = src.CODE
);

INSERT INTO ref_customer_role (CODE, DESCRIPTION)
SELECT src.CODE, src.DESCRIPTION
FROM (
    SELECT 'STANDARD' AS CODE, 'Standard customer' AS DESCRIPTION
    UNION ALL SELECT 'ADMIN', 'Administrative user'
) AS src
WHERE NOT EXISTS (
    SELECT 1 FROM ref_customer_role tgt WHERE tgt.CODE = src.CODE
);

INSERT INTO ref_storage_type (CODE, DESCRIPTION)
SELECT src.CODE, src.DESCRIPTION
FROM (
    SELECT 'TANKER' AS CODE, 'Large tanker storage' AS DESCRIPTION
    UNION ALL SELECT 'SMALL_PURCHASE', 'Small purchase storage'
    UNION ALL SELECT 'UNKNOWN', 'Unknown storage type'
) AS src
WHERE NOT EXISTS (
    SELECT 1 FROM ref_storage_type tgt WHERE tgt.CODE = src.CODE
);

INSERT INTO ref_pump_type (CODE, DESCRIPTION)
SELECT src.CODE, src.DESCRIPTION
FROM (
    SELECT 'BOTH' AS CODE, 'Inside and outside pump' AS DESCRIPTION
    UNION ALL SELECT 'INSIDE', 'Inside pump'
    UNION ALL SELECT 'OUTSIDE', 'Outside pump'
    UNION ALL SELECT 'UNKNOWN', 'Unknown pump type'
) AS src
WHERE NOT EXISTS (
    SELECT 1 FROM ref_pump_type tgt WHERE tgt.CODE = src.CODE
);

INSERT INTO ref_trip_status (CODE, DESCRIPTION, IS_TERMINAL)
SELECT src.CODE, src.DESCRIPTION, src.IS_TERMINAL
FROM (
    SELECT 'COMPLETED' AS CODE, 'Trip completed' AS DESCRIPTION, TRUE AS IS_TERMINAL
    UNION ALL SELECT 'FILLING', 'Trip is filling', FALSE
    UNION ALL SELECT 'UNKNOWN', 'Status not recorded', FALSE
) AS src
WHERE NOT EXISTS (
    SELECT 1 FROM ref_trip_status tgt WHERE tgt.CODE = src.CODE
);

-- ============================================================================
-- Extend rc_user with status & audit columns
-- ============================================================================

SET @col_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'rc_user'
      AND COLUMN_NAME = 'ACCOUNT_STATUS'
);
SET @sql = IF(
    @col_exists = 0,
    'ALTER TABLE rc_user ADD COLUMN ACCOUNT_STATUS VARCHAR(20) NOT NULL DEFAULT ''ACTIVE'' AFTER roles',
    'SELECT ''ACCOUNT_STATUS exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'rc_user'
      AND COLUMN_NAME = 'APPROVED_BY'
);
SET @sql = IF(
    @col_exists = 0,
    'ALTER TABLE rc_user ADD COLUMN APPROVED_BY INT NULL AFTER ACCOUNT_STATUS',
    'SELECT ''APPROVED_BY exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'rc_user'
      AND COLUMN_NAME = 'APPROVED_DATE'
);
SET @sql = IF(
    @col_exists = 0,
    'ALTER TABLE rc_user ADD COLUMN APPROVED_DATE DATETIME NULL AFTER APPROVED_BY',
    'SELECT ''APPROVED_DATE exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'rc_user'
      AND COLUMN_NAME = 'LAST_LOGIN_DATE'
);
SET @sql = IF(
    @col_exists = 0,
    'ALTER TABLE rc_user ADD COLUMN LAST_LOGIN_DATE DATETIME NULL AFTER APPROVED_DATE',
    'SELECT ''LAST_LOGIN_DATE exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'rc_user'
      AND COLUMN_NAME = 'CREATED_DATE'
);
SET @sql = IF(
    @col_exists = 0,
    'ALTER TABLE rc_user ADD COLUMN CREATED_DATE DATETIME NULL AFTER LAST_LOGIN_DATE',
    'SELECT ''CREATED_DATE exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @fk_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'rc_user'
      AND CONSTRAINT_NAME = 'fk_rc_user_account_status'
);
SET @sql = IF(
    @fk_exists = 0,
    'ALTER TABLE rc_user
        ADD CONSTRAINT fk_rc_user_account_status
        FOREIGN KEY (ACCOUNT_STATUS)
        REFERENCES ref_account_status(CODE)',
    'SELECT ''fk_rc_user_account_status exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE rc_user
SET ACCOUNT_STATUS = 'ACTIVE'
WHERE ACCOUNT_STATUS IS NULL OR ACCOUNT_STATUS = '';

UPDATE rc_user
SET CREATED_DATE = COALESCE(CREATED_DATE, NOW())
WHERE CREATED_DATE IS NULL;

-- ============================================================================
-- Extend rs_cust_dtls with admin flag and status code
-- ============================================================================

SET @col_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'rs_cust_dtls'
      AND COLUMN_NAME = 'IS_ADMIN'
);
SET @sql = IF(
    @col_exists = 0,
    'ALTER TABLE rs_cust_dtls ADD COLUMN IS_ADMIN BOOLEAN NOT NULL DEFAULT FALSE AFTER IS_ACTIVE',
    'SELECT ''IS_ADMIN exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'rs_cust_dtls'
      AND COLUMN_NAME = 'STATUS_CODE'
);
SET @sql = IF(
    @col_exists = 0,
    'ALTER TABLE rs_cust_dtls ADD COLUMN STATUS_CODE VARCHAR(20) NULL AFTER IS_ADMIN',
    'SELECT ''STATUS_CODE exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @collation = (
    SELECT TABLE_COLLATION
    FROM INFORMATION_SCHEMA.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'rs_cust_dtls'
);
SET @sql = IF(
    @collation <> 'utf8mb4_0900_ai_ci',
    'ALTER TABLE rs_cust_dtls CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci',
    'SELECT ''rs_cust_dtls already utf8mb4'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @fk_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'rs_cust_dtls'
      AND CONSTRAINT_NAME = 'fk_rs_cust_status'
);
SET @sql = IF(
    @fk_exists = 0,
    'ALTER TABLE rs_cust_dtls
        ADD CONSTRAINT fk_rs_cust_status
        FOREIGN KEY (STATUS_CODE)
        REFERENCES ref_customer_status(CODE)',
    'SELECT ''fk_rs_cust_status exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE rs_cust_dtls
SET STATUS_CODE = CASE
        WHEN IS_ACTIVE = 0 THEN 'INACTIVE'
        WHEN IS_ACTIVE = 1 THEN 'ACTIVE'
        ELSE 'PENDING'
    END
WHERE STATUS_CODE IS NULL;

UPDATE rs_cust_dtls c
LEFT JOIN rc_user u ON u.id = c.USER_ID
SET c.IS_ADMIN = (
        u.roles LIKE '%ROLE_ADMIN%'
    )
WHERE c.USER_ID IS NOT NULL;

-- ============================================================================
-- Introduce lookup-backed codes for logistics tables
-- ============================================================================

SET @col_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wt_purchase_party'
      AND COLUMN_NAME = 'STORAGE_TYPE_CODE'
);
SET @sql = IF(
    @col_exists = 0,
    'ALTER TABLE wt_purchase_party ADD COLUMN STORAGE_TYPE_CODE VARCHAR(30) NULL AFTER storage_type',
    'SELECT ''STORAGE_TYPE_CODE exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @fk_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wt_purchase_party'
      AND CONSTRAINT_NAME = 'fk_wt_party_storage_type'
);
SET @sql = IF(
    @fk_exists = 0,
    'ALTER TABLE wt_purchase_party
        ADD CONSTRAINT fk_wt_party_storage_type
        FOREIGN KEY (STORAGE_TYPE_CODE)
        REFERENCES ref_storage_type(CODE)',
    'SELECT ''fk_wt_party_storage_type exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE wt_purchase_party
SET STORAGE_TYPE_CODE = CASE
        WHEN storage_type = 'Tanker' THEN 'TANKER'
        WHEN storage_type = 'Small Purchase' THEN 'SMALL_PURCHASE'
        ELSE 'UNKNOWN'
    END
WHERE STORAGE_TYPE_CODE IS NULL;

SET @col_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wt_purchase_details'
      AND COLUMN_NAME = 'PUMP_USED_CODE'
);
SET @sql = IF(
    @col_exists = 0,
    'ALTER TABLE wt_purchase_details ADD COLUMN PUMP_USED_CODE VARCHAR(30) NULL AFTER pump_used',
    'SELECT ''PUMP_USED_CODE exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wt_purchase_details'
      AND COLUMN_NAME = 'STATUS_CODE'
);
SET @sql = IF(
    @col_exists = 0,
    'ALTER TABLE wt_purchase_details ADD COLUMN STATUS_CODE VARCHAR(30) NULL AFTER status',
    'SELECT ''STATUS_CODE exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @fk_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wt_purchase_details'
      AND CONSTRAINT_NAME = 'fk_wt_details_pump_type'
);
SET @sql = IF(
    @fk_exists = 0,
    'ALTER TABLE wt_purchase_details
        ADD CONSTRAINT fk_wt_details_pump_type
        FOREIGN KEY (PUMP_USED_CODE)
        REFERENCES ref_pump_type(CODE)',
    'SELECT ''fk_wt_details_pump_type exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @fk_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wt_purchase_details'
      AND CONSTRAINT_NAME = 'fk_wt_details_status'
);
SET @sql = IF(
    @fk_exists = 0,
    'ALTER TABLE wt_purchase_details
        ADD CONSTRAINT fk_wt_details_status
        FOREIGN KEY (STATUS_CODE)
        REFERENCES ref_trip_status(CODE)',
    'SELECT ''fk_wt_details_status exists'' AS Info'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE wt_purchase_details
SET PUMP_USED_CODE = COALESCE(pump_used, 'UNKNOWN')
WHERE PUMP_USED_CODE IS NULL;

UPDATE wt_purchase_details
SET PUMP_USED_CODE = 'UNKNOWN'
WHERE PUMP_USED_CODE NOT IN (SELECT CODE FROM ref_pump_type);

UPDATE wt_purchase_details
SET STATUS_CODE = CASE
        WHEN status = 'COMPLETED' THEN 'COMPLETED'
        WHEN status = 'FILLING' THEN 'FILLING'
        ELSE 'UNKNOWN'
    END
WHERE STATUS_CODE IS NULL;

UPDATE wt_purchase_details
SET STATUS_CODE = 'UNKNOWN'
WHERE STATUS_CODE NOT IN (SELECT CODE FROM ref_trip_status);

-- ============================================================================
-- Ensure lookup relationships are consistent
-- ============================================================================

UPDATE rc_user
SET ACCOUNT_STATUS = 'ACTIVE'
WHERE ACCOUNT_STATUS NOT IN (SELECT CODE FROM ref_account_status);

UPDATE rs_cust_dtls
SET STATUS_CODE = 'ACTIVE'
WHERE STATUS_CODE NOT IN (SELECT CODE FROM ref_customer_status);

SELECT 'V3: Reference normalization completed.' AS Status;

SET SQL_SAFE_UPDATES = 1;

