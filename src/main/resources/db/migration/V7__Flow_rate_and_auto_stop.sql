-- V7: Flow Rate Configuration & Auto-Stop System
-- Based on actual measurements: 500L in 215-220 seconds (both pumps)

-- =====================================================
-- Table 1: Pump Flow Rate Configuration
-- Stores configurable defaults + customer overrides
-- effective_from serves dual purpose: config active date + data cutoff
-- =====================================================
CREATE TABLE IF NOT EXISTS pump_flow_rate_config (
    id INT PRIMARY KEY AUTO_INCREMENT,
    pump_type VARCHAR(20) NOT NULL COMMENT 'INSIDE, OUTSIDE, or BOTH',
    default_sec_per_liter DECIMAL(5,3) NOT NULL COMMENT 'Time per liter in seconds',
    customer_id INT NULL COMMENT 'NULL=global default, INT=customer-specific override',
    effective_from DATETIME NOT NULL COMMENT 'Config active date + historical data cutoff',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    notes VARCHAR(255) NULL,
    created_by INT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NULL ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_pump_customer_active (pump_type, customer_id, is_active),
    INDEX idx_effective_from (effective_from),
    INDEX idx_active_pump (is_active, pump_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Configurable flow rate defaults';

-- =====================================================
-- Table 2: Flow Rate Cache
-- Performance optimization - stores calculated rates
-- =====================================================
CREATE TABLE IF NOT EXISTS pump_flow_rate_cache (
    customer_id INT NOT NULL,
    pump_type VARCHAR(20) NOT NULL,
    calculated_sec_per_liter DECIMAL(5,3) NOT NULL,
    sample_count INT NOT NULL COMMENT 'Number of trips used in calculation',
    last_calculated DATETIME NOT NULL,
    calculation_source VARCHAR(50) NOT NULL COMMENT 'CUSTOMER_HISTORY, GLOBAL_AVG, or CONFIG_DEFAULT',
    PRIMARY KEY (customer_id, pump_type),
    INDEX idx_last_calculated (last_calculated),
    INDEX idx_source (calculation_source)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Cached flow rate calculations';

-- =====================================================
-- Table 3: Trip Flow Rate Anomalies
-- Data quality tracking - outlier detection
-- =====================================================
CREATE TABLE IF NOT EXISTS trip_flow_rate_anomalies (
    id INT PRIMARY KEY AUTO_INCREMENT,
    trip_id INT NOT NULL,
    customer_id INT NOT NULL,
    measured_sec_per_liter DECIMAL(5,3) NOT NULL,
    expected_sec_per_liter DECIMAL(5,3) NOT NULL,
    deviation_percent DECIMAL(5,2) NOT NULL,
    reason VARCHAR(50) NOT NULL COMMENT 'TOO_FAST, TOO_SLOW, DURATION_ZERO',
    flagged_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_by INT NULL,
    review_notes VARCHAR(255) NULL,
    resolution VARCHAR(50) NULL COMMENT 'VALID, INVALID, CAPACITY_ERROR, etc',
    INDEX idx_customer_flagged (customer_id, flagged_at),
    INDEX idx_unreviewed (reviewed_by, flagged_at),
    INDEX idx_trip (trip_id),
    FOREIGN KEY fk_anomaly_trip (trip_id) 
        REFERENCES wt_purchase_details(ID) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Outlier detection log';

-- =====================================================
-- Modify Existing Table: wt_purchase_details
-- Add auto-stop tracking columns (idempotent)
-- =====================================================
SET @dbname = DATABASE();
SET @tablename = 'wt_purchase_details';
SET @preparedStatement = (SELECT IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS 
   WHERE TABLE_SCHEMA = @dbname 
   AND TABLE_NAME = @tablename 
   AND COLUMN_NAME = 'expected_duration_sec') > 0,
  'SELECT 1',
  'ALTER TABLE wt_purchase_details ADD COLUMN expected_duration_sec INT NULL COMMENT ''Calculated ETA at trip start (seconds)'''
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

SET @preparedStatement = (SELECT IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS 
   WHERE TABLE_SCHEMA = @dbname 
   AND TABLE_NAME = @tablename 
   AND COLUMN_NAME = 'auto_stop_scheduled') > 0,
  'SELECT 1',
  'ALTER TABLE wt_purchase_details ADD COLUMN auto_stop_scheduled BOOLEAN NOT NULL DEFAULT FALSE COMMENT ''Backend will auto-stop this trip'''
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

SET @preparedStatement = (SELECT IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS 
   WHERE TABLE_SCHEMA = @dbname 
   AND TABLE_NAME = @tablename 
   AND COLUMN_NAME = 'auto_stopped') > 0,
  'SELECT 1',
  'ALTER TABLE wt_purchase_details ADD COLUMN auto_stopped BOOLEAN NOT NULL DEFAULT FALSE COMMENT ''Was stopped automatically by backend'''
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

SET @preparedStatement = (SELECT IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS 
   WHERE TABLE_SCHEMA = @dbname 
   AND TABLE_NAME = @tablename 
   AND COLUMN_NAME = 'auto_stop_attempted_at') > 0,
  'SELECT 1',
  'ALTER TABLE wt_purchase_details ADD COLUMN auto_stop_attempted_at DATETIME NULL COMMENT ''Timestamp of auto-stop attempt'''
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

-- Performance index for auto-stop query (idempotent)
SET @preparedStatement = (SELECT IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS 
   WHERE TABLE_SCHEMA = @dbname 
   AND TABLE_NAME = @tablename 
   AND INDEX_NAME = 'idx_trip_autostop') > 0,
  'SELECT 1',
  'CREATE INDEX idx_trip_autostop ON wt_purchase_details(STATUS_CODE, auto_stop_scheduled, auto_stopped, start_time)'
));
PREPARE createIndexIfNotExists FROM @preparedStatement;
EXECUTE createIndexIfNotExists;
DEALLOCATE PREPARE createIndexIfNotExists;

-- =====================================================
-- Initial Configuration Data
-- Based on actual measurements: 500L/220s both pumps
-- Single pump estimated as half: ~0.9 sec/L
-- Only insert if records don't exist (idempotent)
-- =====================================================
INSERT IGNORE INTO pump_flow_rate_config 
(pump_type, default_sec_per_liter, customer_id, effective_from, notes) 
VALUES
('INSIDE',  0.90, NULL, NOW(), 'Single pump baseline - measured 500L/220s ÷ 2 = ~0.9 sec/L'),
('OUTSIDE', 0.90, NULL, NOW(), 'Single pump baseline - measured 500L/220s ÷ 2 = ~0.9 sec/L'),
('BOTH',    0.48, NULL, NOW(), 'Both pumps baseline - measured 500L/220s = 0.44, buffered to 0.46 sec/L');

-- =====================================================
-- Migration Complete
-- Summary:
--   + 3 new tables (config, cache, anomalies)
--   + 4 columns to wt_purchase_details
--   + 2 indexes for performance
--   + 3 initial config records
-- =====================================================

