-- V197 stamped restored leads with now(), which made them look freshly active. Put the dates back to the real last
-- touch (the lead's own created_at). Only rows nobody has edited since: updated_at still equals the V197 stamp.
WITH restored AS (
  SELECT e.id AS event_id, e.lead_id
  FROM lead_events e
  JOIN lead_intake l ON l.id = e.lead_id
  WHERE e.type = 'STATUS' AND e.text = 'NEW' AND e.created_by = 'System'
    AND l.legacy AND l.updated_by = 'System' AND abs(extract(epoch FROM (l.updated_at - e.created_at))) < 5
), fix_lead AS (
  UPDATE lead_intake l SET updated_at = l.created_at FROM restored r WHERE l.id = r.lead_id RETURNING l.id
)
UPDATE lead_events e SET created_at = l.created_at FROM restored r JOIN lead_intake l ON l.id = r.lead_id WHERE e.id = r.event_id;
