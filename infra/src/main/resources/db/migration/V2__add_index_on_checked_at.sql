CREATE INDEX idx_health_check_log_checked_at
    ON health_check_log (component, checked_at);
