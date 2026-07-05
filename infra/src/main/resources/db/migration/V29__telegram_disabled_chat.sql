-- V29: persistent allow-list of Telegram chat ids that the bot must NOT send to.
-- Populated automatically when Telegram returns 403 Forbidden (bot kicked, blocked,
-- or removed). Manual re-enable: DELETE FROM telegram_disabled_chat WHERE chat_id = ?
-- Cleared from the in-memory cache on the next periodic reload (or on app restart).

CREATE TABLE telegram_disabled_chat (
    chat_id     BIGINT       PRIMARY KEY,
    reason      VARCHAR(500) NOT NULL,
    disabled_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- No additional indexes needed — primary key on chat_id is the only access pattern
-- (point-lookup by id, full-table scan on periodic reload).
