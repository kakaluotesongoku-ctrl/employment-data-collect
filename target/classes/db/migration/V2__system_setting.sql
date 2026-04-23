CREATE TABLE IF NOT EXISTS system_setting (
    setting_key VARCHAR(100) PRIMARY KEY,
    setting_value VARCHAR(2000),
    updated_at DATETIME(6)
);