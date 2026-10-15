-- ============================================================================
-- The five pre-Flyway tables db/legacy-baseline.sql does not reproduce
-- ============================================================================
-- Loaded AFTER db/legacy-baseline.sql, for the `container` profile only.
--
-- ⚠ WHY A SECOND FILE RATHER THAN EXTENDING THE FIXTURE. db/migration/README.md
-- records that 13 tables predate the Flyway version-0 baseline and no migration
-- creates them. legacy-baseline.sql reproduces the 8 the migration gates need,
-- and deliberately stops there — the gates only ever foreign-key those, so
-- growing the fixture would add surface nothing asserts. The APPLICATION needs
-- all 13: PumpAutoStopService's @PostConstruct recoverActiveTrips() queries
-- wt_purchase_details before the context finishes, so without these five the app
-- does not start at all. Found by trying to start it.
--
-- ⚠ THIS IS NOT THE OWED V0. db/migration/README.md and implementation-plan.md
-- §0.4 both record that a blank database still cannot be built from the repo and
-- that a V0 baseline migration is owed. This file is a DEVELOPER CONVENIENCE for
-- one throwaway container, not that migration: V0 has to be reviewed against
-- production's real DDL, and these shapes are read off the JPA mappings instead.
-- Where the two differ, production wins. Keeping them separate means nobody
-- mistakes this for the real thing.
--
-- Shapes derived from the entity mappings, column for column:
--   rc_usr_api_kys       user/model/UserApiKey.java
--   rc_pn_tokens         notification/model/PushNotificationToken.java
--   wt_purchase_party    water/model/WaterPurchaseParty.java
--   wt_purchase_details  customer/model/CustomerTripLedger.java
--   wt_adt_lgs           water/model/AuditLog.java
--
-- No rows. Like the fixture, this carries structure only.
-- ============================================================================

CREATE TABLE IF NOT EXISTS rc_usr_api_kys (
    id             INT          NOT NULL AUTO_INCREMENT,
    user_id        INT          NOT NULL,
    api_key        VARCHAR(255) NOT NULL,
    water_party_id INT          NULL,
    created_date   DATETIME     NOT NULL,
    expiry_date    DATETIME     NOT NULL,
    last_used_date DATETIME     NULL,
    is_active      TINYINT(1)   NOT NULL DEFAULT 1,
    device_info    VARCHAR(255) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_api_key (api_key),
    KEY idx_api_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS rc_pn_tokens (
    id           INT          NOT NULL AUTO_INCREMENT,
    user_id      INT          NOT NULL,
    device_token VARCHAR(255) NOT NULL,
    platform     VARCHAR(20)  NOT NULL,
    created_date DATETIME     NOT NULL,
    last_updated DATETIME     NOT NULL,
    is_active    TINYINT(1)   NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_device_token (device_token),
    KEY idx_pn_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS wt_purchase_party (
    id                INT          NOT NULL AUTO_INCREMENT,
    customer_id       INT          NOT NULL,
    STORAGE_TYPE_CODE VARCHAR(30)  NULL,
    capacity          INT          NOT NULL,
    vehicle_number    VARCHAR(255) NULL,
    registration_date DATE         NOT NULL,
    address           VARCHAR(255) NULL,
    notes             TEXT         NULL,
    approved_by       INT          NULL,
    approved_date     DATETIME     NULL,
    is_active         TINYINT(1)   NOT NULL DEFAULT 1,
    contact_number    VARCHAR(20)  NULL,
    location          VARCHAR(255) NULL,
    PRIMARY KEY (id),
    KEY idx_party_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ⚠ The water trip ledger, and the one PumpAutoStopService reads at startup.
-- Note that CustomerTripLedger's @Id carries no @GeneratedValue, so Hibernate
-- expects the application to supply ID; AUTO_INCREMENT here is harmless and
-- matches what the legacy app relies on.
CREATE TABLE IF NOT EXISTS wt_purchase_details (
    ID                     INT           NOT NULL AUTO_INCREMENT,
    CUST_ID                INT           NOT NULL,
    TRIP_DATE_TIME         DATETIME      NOT NULL,
    CREDIT_AMOUNT          DECIMAL(10,2) NULL,
    DEPOSIT_AMOUNT         DECIMAL(10,2) NULL,
    BALANCE_AMOUNT         DECIMAL(10,2) NULL,
    CRE_BY                 VARCHAR(255)  NOT NULL,
    PUMP_USED_CODE         VARCHAR(30)   NULL,
    STATUS_CODE            VARCHAR(30)   NULL,
    start_time             DATETIME      NULL,
    end_time               DATETIME      NULL,
    expected_duration_sec  INT           NULL,
    auto_stop_scheduled    TINYINT(1)    NULL DEFAULT 0,
    auto_stopped           TINYINT(1)    NULL DEFAULT 0,
    auto_stop_attempted_at DATETIME      NULL,
    PRIMARY KEY (ID),
    KEY idx_trip_cust (CUST_ID, TRIP_DATE_TIME),
    KEY idx_trip_status (STATUS_CODE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS wt_adt_lgs (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    user_id              INT          NOT NULL,
    action_type          VARCHAR(50)  NOT NULL,
    action_description   VARCHAR(500) NULL,
    target_entity        VARCHAR(50)  NULL,
    target_id            INT          NULL,
    performed_by         INT          NOT NULL,
    ip_address           VARCHAR(45)  NULL,
    timestamp            DATETIME     NOT NULL,
    is_chargeable_action TINYINT(1)   NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    KEY idx_audit_time (timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SELECT 'local-dev: the five remaining pre-Flyway tables are present.' AS Status;
