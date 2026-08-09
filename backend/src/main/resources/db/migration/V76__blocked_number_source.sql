-- Distinguishes a manager's manual "Block number" action from an automatic block triggered by
-- the customer texting a standard opt-out keyword (STOP/UNSUBSCRIBE/etc.) — see
-- TwilioInboundSmsController. Existing rows are all manual blocks (opt-out auto-blocking didn't
-- exist before this), hence the backfilled default.
ALTER TABLE blocked_number ADD COLUMN source TEXT NOT NULL DEFAULT 'MANUAL';
