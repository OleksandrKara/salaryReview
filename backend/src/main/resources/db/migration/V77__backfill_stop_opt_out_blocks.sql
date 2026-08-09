-- One-time backfill: the STOP-opt-out auto-block feature (see TwilioInboundSmsController) only
-- takes effect for inbound messages received after it shipped. A few customers replied STOP
-- earlier the same day, before the fix went live, and were logged but never blocked — this
-- catches them up using the exact same exact-whole-body keyword match the live code uses, so
-- they stop receiving any further texts. ON CONFLICT DO NOTHING makes this safe to have run
-- alongside any number already blocked (manually or by the live feature) for the same reason.
INSERT INTO blocked_number (phone_number, blocked_at, source)
SELECT DISTINCT phone_number, now(), 'STOP_REQUEST'
FROM sms_message
WHERE direction = 'INBOUND'
  AND upper(trim(body)) IN ('STOP', 'STOPALL', 'UNSUBSCRIBE', 'CANCEL', 'END', 'QUIT')
ON CONFLICT (phone_number) DO NOTHING;
