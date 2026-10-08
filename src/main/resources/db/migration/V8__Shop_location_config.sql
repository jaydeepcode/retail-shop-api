-- ============================================================================
-- V8: Shop Location Config (RECONSTRUCTED — see the note below)
-- ============================================================================
-- Geofencing configuration for the water counter: the shop's coordinates and
-- the radius a customer must be within for location validation to pass.
--
-- ⚠ THIS FILE IS A RECONSTRUCTION, NOT THE ORIGINAL.
--
-- The original V8__Shop_location_config.sql was applied to production on
-- 2025-12-07 20:19:35 (flyway_schema_history rank 8, checksum 1287378492) and
-- was never committed to version control. It is not in any commit on any
-- branch, in any reachable or dangling git object, or anywhere on the
-- developer's filesystem. It is gone.
--
-- This file reproduces its EFFECT on the schema, taken from the production
-- dump's DDL for the table it created (rechargeData.sql:572-584). A blank
-- environment built from this repo therefore converges on the production
-- schema, which it could not do before — see design-crosscutting.md:596-598
-- (risk R2) and design-domain.md §6.2.
--
-- CONSEQUENCE FOR PRODUCTION: these bytes do not hash to 1287378492, so
-- Flyway's startup validation will fail with a checksum mismatch on version 8
-- until `flyway repair` is run once against production to rewrite that one
-- history row. Production's SCHEMA and DATA are untouched by this; only the
-- recorded checksum changes. design-domain.md §6.2 names this as the cost of
-- closing the gap properly rather than folding it into V9.
--
-- WHAT THIS FILE DELIBERATELY OMITS: the original V8 also inserted the shop's
-- actual latitude and longitude (production row id=1 carries radius 15 and
-- validation enabled, and its updated_at is V8's install timestamp to the
-- second, so V8 wrote it). Those coordinates are NOT reproduced here, because
-- this repository is public and they are the owner's physical premises. The
-- column defaults below leave a fresh environment with validation DISABLED,
-- which is the safe default; production already holds its own row and is
-- unaffected. If the repository is made private (secret-rotation.md §4.5),
-- seeding the real coordinates here becomes an option.
-- ============================================================================

-- The FK is intentionally left unnamed so MySQL generates
-- `shop_location_config_ibfk_1`, which is the constraint name production
-- carries — evidence the original did not name it either.
CREATE TABLE IF NOT EXISTS shop_location_config (
    id                             INT           NOT NULL AUTO_INCREMENT,
    latitude                       DECIMAL(10,8) NOT NULL COMMENT 'Shop latitude coordinate',
    longitude                      DECIMAL(11,8) NOT NULL COMMENT 'Shop longitude coordinate',
    radius_in_meters               INT           NOT NULL DEFAULT '5'
        COMMENT 'Allowed radius in meters (default: 5m)',
    is_location_validation_enabled TINYINT(1)    NOT NULL DEFAULT '0'
        COMMENT 'Feature toggle: enable/disable location validation',
    updated_at                     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,
    updated_by                     INT           DEFAULT NULL
        COMMENT 'User ID who last updated the configuration',
    PRIMARY KEY (id),
    KEY updated_by (updated_by),
    FOREIGN KEY (updated_by) REFERENCES rc_user (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Shop location configuration for geofencing';
