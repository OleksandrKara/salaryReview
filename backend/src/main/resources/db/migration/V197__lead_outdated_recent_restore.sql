-- Recent people (created in the last 60 days) do not belong in the Outdated archive: they are live leads.
-- All go to NEW; LeadFollowUpService.autoBook moves the ones with a real (non-consultation) booking to BOOKED.
WITH moved AS (
  UPDATE lead_intake SET status = 'NEW', updated_by = 'System', updated_at = now()
  WHERE status = 'OUTDATED' AND legacy AND created_at >= now() - interval '60 days'
  RETURNING id, business_id
)
INSERT INTO lead_events (lead_id, business_id, type, text, created_by)
SELECT id, business_id, 'STATUS', 'NEW', 'System' FROM moved;
