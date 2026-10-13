-- ai-visibility (owner request 2026-10-13): where the Google engines search from, per business, as
-- named points; "near me" questions asked only to Google AI Overviews / AI Mode, from every point.
-- PMU points follow where its clients actually live (Square customer ZIPs, 667 with an address:
-- North 28%, Central 25%, East 16%, South Bay 14%, other 15%). The first point is the default
-- for ordinary questions. Nails has almost no ZIPs on file and draws from around the studio.
ALTER TABLE ai_visibility_settings ADD COLUMN search_points JSONB NOT NULL DEFAULT '[]';
UPDATE ai_visibility_settings SET search_points =
  '[{"label":"Downtown San Diego","coordinate":"32.7157,-117.1611,14"}]'::jsonb WHERE business_id = 1;
UPDATE ai_visibility_settings SET search_points = '[
  {"label":"Central (Hillcrest, North Park, Downtown)","coordinate":"32.7480,-117.1550,13","clientShare":25},
  {"label":"North (UTC, La Jolla, Carmel Valley)","coordinate":"32.8707,-117.2108,13","clientShare":28},
  {"label":"East (La Mesa, El Cajon)","coordinate":"32.7678,-117.0231,13","clientShare":16},
  {"label":"South Bay (Chula Vista)","coordinate":"32.6401,-117.0842,13","clientShare":14}
]'::jsonb WHERE business_id = 2;

ALTER TABLE ai_visibility_query ADD COLUMN engines JSONB;          -- null = every engine
ALTER TABLE ai_visibility_query ADD COLUMN all_points BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ai_visibility_check ADD COLUMN search_point TEXT;

INSERT INTO ai_visibility_query (business_id, query_text, topic, target_url, status, sort_order, activated_at, engines, all_points)
SELECT 1, 'nail salon near me', 'near me', 'https://akluxnails.com/', 'ACTIVE', 0, now(), '["GOOGLE_AI_OVERVIEW","GOOGLE_AI_MODE"]'::jsonb, TRUE
WHERE EXISTS (SELECT 1 FROM business WHERE id = 1);
INSERT INTO ai_visibility_query (business_id, query_text, topic, target_url, status, sort_order, activated_at, engines, all_points)
SELECT 2, 'permanent makeup near me', 'near me', 'https://pmu-annakara.com/', 'ACTIVE', 0, now(), '["GOOGLE_AI_OVERVIEW","GOOGLE_AI_MODE"]'::jsonb, TRUE
WHERE EXISTS (SELECT 1 FROM business WHERE id = 2);
