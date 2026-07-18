-- Single-row runtime config for the 4-hand-request Telegram alert, shared by mani and
-- akluxnails-home (one bot/chat for both). Owner-editable at /api/owner/settings/telegram; the
-- bot token never leaves this backend — mani/akluxnails call an internal *notify* endpoint
-- (POST /api/internal/notifications/four-hand-request), not a config-fetch endpoint, so the
-- token is never handed to another codebase/server.
--
-- Seeded NULL deliberately — never put a live secret in a git-committed migration. The real
-- bot_token/chat_id are set once via the owner UI after this deploys.
CREATE TABLE telegram_notification_config (
    id         BOOLEAN     PRIMARY KEY DEFAULT true CHECK (id),  -- enforces exactly one row
    bot_token  TEXT,          -- null/blank => notifications silently skipped
    chat_id    TEXT,          -- null/blank => notifications silently skipped
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by TEXT
);

INSERT INTO telegram_notification_config (id, bot_token, chat_id, updated_by)
VALUES (true, NULL, NULL, NULL);
