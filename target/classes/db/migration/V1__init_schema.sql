CREATE TABLE IF NOT EXISTS user_account (
    id BIGINT PRIMARY KEY,
    username VARCHAR(100) NOT NULL,
    normalized_username VARCHAR(100),
    role VARCHAR(30) NOT NULL,
    city_name VARCHAR(100),
    phone VARCHAR(30),
    salt VARCHAR(128),
    password_hash VARCHAR(256),
    enabled BIT NOT NULL,
    failed_login_count INT,
    last_failed_login_at DATETIME(6),
    locked_until DATETIME(6),
    sms_challenge_code VARCHAR(20),
    sms_challenge_expires_at DATETIME(6),
    enterprise_id BIGINT,
    last_known_ip VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS enterprise_profile (
    id BIGINT PRIMARY KEY,
    enterprise_user_id BIGINT,
    region_province VARCHAR(100),
    city_name VARCHAR(100),
    county_name VARCHAR(100),
    org_code VARCHAR(100),
    enterprise_name VARCHAR(200),
    enterprise_nature VARCHAR(100),
    industry VARCHAR(100),
    contact_name VARCHAR(100),
    contact_phone VARCHAR(50),
    address VARCHAR(500),
    status VARCHAR(40),
    review_reason VARCHAR(1000),
    reviewed_by BIGINT,
    reviewed_at DATETIME(6),
    submitted_at DATETIME(6),
    updated_at DATETIME(6),
    latest_period_name VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS survey_period (
    id BIGINT PRIMARY KEY,
    name VARCHAR(100),
    start_date DATE,
    end_date DATE,
    submission_start DATE,
    submission_end DATE,
    active BIT NOT NULL,
    updated_at DATETIME(6)
);

CREATE TABLE IF NOT EXISTS monthly_report (
    id BIGINT PRIMARY KEY,
    enterprise_id BIGINT NOT NULL,
    enterprise_name VARCHAR(200),
    city_name VARCHAR(100),
    period_id BIGINT NOT NULL,
    period_name VARCHAR(100),
    archived_jobs INT,
    survey_jobs INT,
    other_reason TEXT,
    decrease_type VARCHAR(255),
    main_reason VARCHAR(255),
    main_reason_description TEXT,
    secondary_reason VARCHAR(255),
    secondary_reason_description TEXT,
    third_reason VARCHAR(255),
    third_reason_description TEXT,
    status VARCHAR(40),
    city_reviewed_by BIGINT,
    city_reviewed_at DATETIME(6),
    city_review_reason TEXT,
    province_reviewed_by BIGINT,
    province_reviewed_at DATETIME(6),
    province_review_reason TEXT,
    submitted_at DATETIME(6),
    created_at DATETIME(6),
    updated_at DATETIME(6),
    province_adjustment_archived_jobs INT,
    province_adjustment_survey_jobs INT,
    province_adjustment_other_reason TEXT,
    province_adjustment_decrease_type VARCHAR(255),
    province_adjustment_main_reason VARCHAR(255),
    province_adjustment_main_reason_description TEXT,
    province_adjustment_secondary_reason VARCHAR(255),
    province_adjustment_secondary_reason_description TEXT,
    province_adjustment_third_reason VARCHAR(255),
    province_adjustment_third_reason_description TEXT,
    province_adjustment_adjust_reason TEXT,
    province_adjustment_adjusted_by BIGINT,
    province_adjustment_adjusted_at DATETIME(6)
);

CREATE TABLE IF NOT EXISTS notice_record (
    id BIGINT PRIMARY KEY,
    title VARCHAR(100),
    content TEXT,
    applies_to_all BIT NOT NULL,
    publisher_id BIGINT,
    publisher_role VARCHAR(30),
    publisher_name VARCHAR(100),
    status VARCHAR(30),
    created_at DATETIME(6),
    updated_at DATETIME(6),
    deleted_at DATETIME(6)
);

CREATE TABLE IF NOT EXISTS notice_target_city (
    notice_id BIGINT NOT NULL,
    city_name VARCHAR(100) NOT NULL,
    PRIMARY KEY (notice_id, city_name)
);

CREATE TABLE IF NOT EXISTS audit_log (
    id BIGINT PRIMARY KEY,
    action VARCHAR(80),
    target_type VARCHAR(80),
    target_id BIGINT,
    description VARCHAR(2000),
    actor_id BIGINT,
    actor_name VARCHAR(100),
    client_ip VARCHAR(64),
    created_at DATETIME(6)
);

CREATE INDEX idx_user_account_username ON user_account (normalized_username);
CREATE INDEX idx_report_period_status ON monthly_report (period_id, status);
CREATE INDEX idx_enterprise_city_status ON enterprise_profile (city_name, status);
CREATE INDEX idx_notice_status ON notice_record (status);
CREATE INDEX idx_audit_created_at ON audit_log (created_at);
