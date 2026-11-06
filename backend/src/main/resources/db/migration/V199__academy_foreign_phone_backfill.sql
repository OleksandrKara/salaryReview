-- Academy leads with a non-US phone were stored without it, so they split from the same person's site contact row
-- (no Academy mark on that row). Give them their number back; the person key then merges the rows.
UPDATE lead_intake l SET phone_number = '+' || regexp_replace(a.phone, '\D', '', 'g')
FROM academy_lead a
WHERE l.channel = 'Academy' AND l.phone_number IS NULL AND l.external_id = 'academy:' || a.id::text
  AND a.phone LIKE '+%' AND regexp_replace(a.phone, '\D', '', 'g') !~ '^1'
  AND length(regexp_replace(a.phone, '\D', '', 'g')) BETWEEN 8 AND 15;
