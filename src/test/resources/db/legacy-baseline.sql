-- ============================================================================
-- Legacy schema baseline for migration tests
-- ============================================================================
-- WHAT THIS IS: the pre-Flyway legacy tables that V9-V12 touch, in the exact
-- shape production has them. A container loaded with this file and baselined at
-- Flyway version 8 is a structural stand-in for production, which is what
-- implementation-plan.md:69's gate means by "a prod copy".
--
-- WHY IT HAS TO EXIST: 13 of production's tables predate Flyway's version-0
-- baseline and no migration creates them (see db/migration/README.md). Without
-- this file there is nothing for V9+ to be applied to, and the migrations
-- cannot be tested at all.
--
-- WHAT IS DELIBERATELY ABSENT: all customer, transaction, credit, water,
-- credential and audit DATA. This repository is PUBLIC. Only structure and
-- non-sensitive reference codes live here. rc_user and rs_cust_dtls are created
-- empty and reduced to the key columns the new foreign keys point at.
--
-- SCOPE: this is a minimal faithful fixture, not a full schema copy. It carries
-- the eight tables V9-V12 reference and, of those, the columns, types,
-- collations and keys the migrations depend on. Every value below was read from
-- INFORMATION_SCHEMA on the read-only restore on 2026-10-08 — in particular the
-- utf8mb3 collations, which are the whole point of V9's conversion step, and
-- the mediumint keys, which §4.4 names as the most likely cause of a failed
-- first attempt.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Parents of the new foreign keys. Both are utf8mb4_0900_ai_ci in production.
-- ----------------------------------------------------------------------------
-- ⚠ ALSO EXTENDED 2026-10-12, for the same reason as rs_cust_dtls below: the
-- credit statement joins USER_NAME to say who extended each credit, which is
-- the whole of the authorisation control for credit (design-api.md §3.5), and
-- ROLES is what log §15.11's "ROLE_USER already is the staff role" is about.
-- Columns read from the User entity's mapping. No rows.
CREATE TABLE rc_user (
    id              INT          NOT NULL AUTO_INCREMENT,
    USER_NAME       VARCHAR(45)  NOT NULL,
    PASSWORD        VARCHAR(80)  NOT NULL,
    ROLES           VARCHAR(80)  NOT NULL,
    ACCOUNT_STATUS  VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    APPROVED_BY     INT          NULL,
    APPROVED_DATE   DATETIME     NULL,
    LAST_LOGIN_DATE DATETIME     NULL,
    CREATED_DATE    DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_name (USER_NAME)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ⚠ EXTENDED 2026-10-12 from a bare CUST_ID stub to production's real shape.
-- The stub was enough for S1's and S2's gates, which only ever foreign-key
-- against it, but not for S3: the credit book joins FIRST_NAME / LAST_NAME /
-- CONTACT_NUM and the counter searches them. Flyway baselines at version 8, so
-- V1 -- whose first statement is the ALTER that would add these in a real
-- database -- never runs here and cannot supply them.
--
-- Columns read from CustomerDetails' own mapping rather than guessed, so the
-- entity and the fixture agree by construction. Still DATA-FREE: the production
-- dump is 9.2MB of real customer data and this repository is public.
CREATE TABLE rs_cust_dtls (
    CUST_ID     INT          NOT NULL AUTO_INCREMENT,
    FIRST_NAME  VARCHAR(45)  NOT NULL,
    LAST_NAME   VARCHAR(45)  NOT NULL,
    CONTACT_NUM VARCHAR(45)  NOT NULL,
    EMAIL       VARCHAR(100) NULL,
    USER_ID     INT          NULL,
    IS_ADMIN    TINYINT(1)   NOT NULL DEFAULT 0,
    STATUS_CODE VARCHAR(20)  NULL DEFAULT 'PENDING',
    CRE_DTTM    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UPD_DTTM    DATETIME     NULL,
    PRIMARY KEY (CUST_ID),
    KEY idx_cust_contact (CONTACT_NUM)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- The option lookup. Already utf8mb4_0900_ai_ci in production, so V9 leaves it
-- alone and V10 can foreign-key against it directly.
--
-- Note the primary key carries rc_opt_sequence as a third column, which is why
-- (type, option) is not unique by construction and why V10 has to add
-- uk_opt_type_option before rc_operator can reference it (§3.1 fact 2).
-- ----------------------------------------------------------------------------
CREATE TABLE rc_option_lookup_hdr (
    rc_option_type     VARCHAR(5)  NOT NULL,
    rc_option_tp_descr VARCHAR(45) NOT NULL,
    PRIMARY KEY (rc_option_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE rc_option_lookup_dtl (
    rc_option_type   VARCHAR(5)  NOT NULL,
    rc_option        VARCHAR(7)  NOT NULL,
    rc_opt_descr     VARCHAR(45) NOT NULL,
    rc_opt_sw        TINYINT(1)  NOT NULL,
    rc_opt_sequence  INT         NOT NULL,
    PRIMARY KEY (rc_option_type, rc_option, rc_opt_sequence)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------------------------------------------------------
-- The four utf8mb3_general_ci tables. The collation is NOT a transcription
-- slip — it is production's, and V9's conversion of exactly these four is what
-- makes V12's DISHTV_NO foreign key and V15's ref_company join possible
-- (§8.9). A fixture that created them as utf8mb4 would make V9 look correct
-- while testing nothing.
-- ----------------------------------------------------------------------------
CREATE TABLE rc_txn_header (
    TXN_ID        MEDIUMINT     NOT NULL AUTO_INCREMENT,
    TXN_DTTM      DATETIME      NOT NULL,
    txn_total_amt DECIMAL(38,2) NOT NULL,
    amt_tndred    DECIMAL(38,2) NOT NULL,
    amt_returned  DECIMAL(38,2) NOT NULL,
    PRIMARY KEY (TXN_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_general_ci;

CREATE TABLE rc_txn_details (
    SEQ_NO       MEDIUMINT     NOT NULL AUTO_INCREMENT,
    TXN_ID       MEDIUMINT     NOT NULL,
    txn_flg      VARCHAR(255)  NOT NULL,
    reference_no VARCHAR(255)  NULL,
    amount       DECIMAL(38,2) NULL,
    ref_company  VARCHAR(255)  NULL,
    PRIMARY KEY (TXN_ID, SEQ_NO),
    KEY SEQ_NO (SEQ_NO),
    CONSTRAINT TXN_ID FOREIGN KEY (TXN_ID) REFERENCES rc_txn_header (TXN_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_general_ci;

-- CRED_DTTM_UNIQUE is reproduced because it is real and because V28 drops it
-- permanently (§6.4) — a later migration test needs to find it here to drop.
-- design-domain.md §6.3 had assigned the drop to V17, but V17 sits behind V13
-- and V14 in a chain that out-of-order=false will not let S3 skip, so V28 takes
-- it. RechargeCounterMigrationTest is the test that finds it here.
-- It is on CRED_DTTM alone, so it forbids two different customers being given
-- credit in the same second, which is an accident rather than a business rule.
CREATE TABLE rc_credit_req (
    CRE_REQ_ID INT           NOT NULL AUTO_INCREMENT,
    CUST_ID    INT           NOT NULL,
    CRED_DTTM  DATETIME      NOT NULL,
    req_amt    DECIMAL(38,2) NOT NULL,
    req_type   VARCHAR(255)  NOT NULL,
    PRIMARY KEY (CRE_REQ_ID),
    UNIQUE KEY CRED_DTTM_UNIQUE (CRED_DTTM)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_general_ci;

CREATE TABLE rc_dishtv_dtls (
    DISHTV_NO   VARCHAR(12) NOT NULL,
    DISHTV_TYP  VARCHAR(10) NULL,
    CONTACT_NUM VARCHAR(45) NULL,
    NAME        VARCHAR(45) NULL,
    PRIMARY KEY (DISHTV_NO)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_general_ci;

-- ----------------------------------------------------------------------------
-- Reference codes, not business data. These 18 rows are what V10 seeds
-- rc_operator FROM, so without them the operator catalog would come out
-- holding only the two UNKNOWN sentinels and V10 would be untested.
-- Verbatim from the restore, including rc_opt_sw=0 on RNRL and bare SRVTE.
-- ----------------------------------------------------------------------------
INSERT INTO rc_option_lookup_hdr (rc_option_type, rc_option_tp_descr) VALUES
    ('MOBL',  'Mobile Operator'),
    ('TVOPR', 'TV Operator');

INSERT INTO rc_option_lookup_dtl
    (rc_option_type, rc_option, rc_opt_descr, rc_opt_sw, rc_opt_sequence) VALUES
    ('MOBL',  'BSNL',    'Bsnl',                1,  1),
    ('MOBL',  'ARTL',    'Airtel',              1,  2),
    ('MOBL',  'IDEA',    'Idea',                1,  3),
    ('MOBL',  'RJIO',    'Jio',                 1,  4),
    ('MOBL',  'RNRL',    'Reliance',            0,  5),
    ('MOBL',  'VDFN',    'Vodafone',            1,  6),
    ('MOBL',  'SRVTEA',  'Sar Airtel',          1,  7),
    ('MOBL',  'SRVTEB',  'Sar Bsnl',            1,  8),
    ('MOBL',  'SRVTEI',  'Sar Idea',            1,  9),
    ('MOBL',  'SRVTEJ',  'Sar Jio',             1, 10),
    ('MOBL',  'SRVTEV',  'Sar Vodafone',        1, 11),
    ('MOBL',  'SRVTE',   'Sar Comm',            0, 12),
    ('TVOPR', 'AIRLTV',  'Airtel Tv',           1,  0),
    ('TVOPR', 'BIGTV',   'Big Tv',              1,  0),
    ('TVOPR', 'DISHTV',  'Dish Tv',             1,  0),
    ('TVOPR', 'SRVTED',  'Saravate Comm Dish',  1,  0),
    ('TVOPR', 'TATASKY', 'Tata Sky',            1,  0),
    ('TVOPR', 'VIDCTV',  'Videocon D2h',        1,  0);
