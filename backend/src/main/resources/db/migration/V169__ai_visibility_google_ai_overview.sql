-- ai-visibility: Google AI Overviews (the AI answer in the normal Google results) as a sixth engine,
-- next to Google AI Mode (owner decision 2026-10-13 after comparing with InWeb's VYDAI).
ALTER TABLE ai_visibility_check DROP CONSTRAINT ai_visibility_check_engine_check;
ALTER TABLE ai_visibility_check ADD CONSTRAINT ai_visibility_check_engine_check
    CHECK (engine IN ('CHATGPT','GOOGLE_AI_MODE','GEMINI','PERPLEXITY','CLAUDE','GOOGLE_AI_OVERVIEW'));

-- Where the Google engines (AI Mode, AI Overviews) search from: a point in central Downtown San
-- Diego (5th Ave & Broadway, ~0.5 mi from the studio), not the studio itself (which would flatter
-- us) and not just "San Diego" (a city-wide average). Same point for both businesses: they share
-- 1357 Seventh Ave. Format: "lat,lng,zoom". The LLM engines take location from the question text.
ALTER TABLE ai_visibility_settings ADD COLUMN search_coordinate TEXT;
UPDATE ai_visibility_settings SET search_coordinate = '32.7157,-117.1611,14' WHERE business_id IN (1, 2);
