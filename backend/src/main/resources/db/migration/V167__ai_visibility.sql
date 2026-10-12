-- ai-visibility-monitoring (openspec/changes/ai-visibility-monitoring design.md D2, D9, D10):
-- weekly "does the AI assistant recommend us" checks per business, owner-approved questions,
-- per-check history, page component snapshots for the competitor comparison.

CREATE TABLE ai_visibility_settings (
    business_id        BIGINT PRIMARY KEY REFERENCES business(id),
    brand_aliases      JSONB NOT NULL,
    own_domains        JSONB NOT NULL,
    location_hint      TEXT NOT NULL,
    monthly_budget_usd NUMERIC(8,2) NOT NULL DEFAULT 8.00,
    digest_enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    next_actions       TEXT,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ai_visibility_query (
    id           BIGSERIAL PRIMARY KEY,
    business_id  BIGINT NOT NULL REFERENCES business(id),
    query_text   TEXT NOT NULL,
    topic        TEXT,
    target_url   TEXT,
    status       TEXT NOT NULL CHECK (status IN ('PROPOSED','ACTIVE','PAUSED','REMOVED')),
    sort_order   INT NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX ai_visibility_query_text_uq ON ai_visibility_query (business_id, lower(query_text));

CREATE TABLE ai_visibility_run (
    id             BIGSERIAL PRIMARY KEY,
    business_id    BIGINT NOT NULL REFERENCES business(id),
    trigger        TEXT NOT NULL CHECK (trigger IN ('SCHEDULED','MANUAL_QUERY')),
    started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at    TIMESTAMPTZ,
    checks_ok      INT NOT NULL DEFAULT 0,
    checks_failed  INT NOT NULL DEFAULT 0,
    cost_usd       NUMERIC(8,4) NOT NULL DEFAULT 0,
    stopped_reason TEXT
);
CREATE INDEX ai_visibility_run_business_idx ON ai_visibility_run (business_id, started_at DESC);

CREATE TABLE ai_visibility_check (
    id             BIGSERIAL PRIMARY KEY,
    business_id    BIGINT NOT NULL REFERENCES business(id),
    run_id         BIGINT NOT NULL REFERENCES ai_visibility_run(id),
    query_id       BIGINT NOT NULL REFERENCES ai_visibility_query(id),
    engine         TEXT NOT NULL CHECK (engine IN ('CHATGPT','GOOGLE_AI_MODE','GEMINI','PERPLEXITY','CLAUDE')),
    model          TEXT,
    checked_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    status         TEXT NOT NULL CHECK (status IN ('OK','NO_ANSWER','ERROR')),
    mentioned      BOOLEAN,
    mention_rank   SMALLINT,
    own_site_cited BOOLEAN,
    places_named   JSONB NOT NULL DEFAULT '[]',
    sources_cited  JSONB NOT NULL DEFAULT '[]',
    sub_queries    JSONB NOT NULL DEFAULT '[]',
    answer_excerpt TEXT,
    cost_usd       NUMERIC(8,4),
    error          TEXT
);
CREATE INDEX ai_visibility_check_lookup_idx ON ai_visibility_check (business_id, query_id, engine, checked_at DESC);
CREATE INDEX ai_visibility_check_run_idx ON ai_visibility_check (run_id);

CREATE TABLE ai_visibility_page (
    id                 BIGSERIAL PRIMARY KEY,
    business_id        BIGINT NOT NULL REFERENCES business(id),
    url                TEXT NOT NULL,
    is_own             BOOLEAN NOT NULL,
    last_fetched_at    TIMESTAMPTZ,
    http_status        INT,
    fetch_method       TEXT CHECK (fetch_method IN ('DIRECT','ONPAGE','ONPAGE_JS')),
    content_hash       TEXT,
    components         JSONB NOT NULL DEFAULT '{}',
    metrics            JSONB NOT NULL DEFAULT '{}',
    components_history JSONB NOT NULL DEFAULT '[]',
    error              TEXT,
    UNIQUE (business_id, url)
);

INSERT INTO business_feature (business_id, feature_key, enabled)
SELECT id, 'ai-visibility.enabled', false FROM business
WHERE NOT EXISTS (SELECT 1 FROM business_feature f WHERE f.business_id = business.id AND f.feature_key = 'ai-visibility.enabled');

-- Seeds below only for the two real businesses; a fresh database (tests, new installs) without
-- them is left empty.
INSERT INTO ai_visibility_settings (business_id, brand_aliases, own_domains, location_hint)
SELECT 1, '["AK.LUX.NAILS","AK Lux Nails","AKLuxNails","AK Lux"]'::jsonb, '["akluxnails.com"]'::jsonb, 'San Diego,California,United States'
WHERE EXISTS (SELECT 1 FROM business WHERE id = 1);
INSERT INTO ai_visibility_settings (business_id, brand_aliases, own_domains, location_hint)
SELECT 2, '["Anna Kara","PMU Anna Kara","Anna Kara''s Permanent Makeup Studio","Anna Kara''s Beauty PMU Studio","Anna Kara Academy"]'::jsonb,
       '["pmu-annakara.com"]'::jsonb, 'San Diego,California,United States'
WHERE EXISTS (SELECT 1 FROM business WHERE id = 2);

INSERT INTO ai_visibility_query (business_id, query_text, topic, target_url, status, sort_order)
SELECT 1, q.text, q.topic, q.url, 'PROPOSED', q.ord
FROM (VALUES
  (1, 'recommend me the best place to do Russian manicure in San Diego', 'russian manicure', 'https://akluxnails.com/'),
  (2, 'best Russian manicure in San Diego', 'russian manicure', 'https://akluxnails.com/'),
  (3, 'who does the most precise dry e-file cuticle manicure in San Diego', 'russian manicure', 'https://akluxnails.com/blog/russian-manicure-explained'),
  (4, 'how much is a Russian manicure in San Diego and where is it worth it', 'russian manicure', 'https://akluxnails.com/prices'),
  (5, 'best place for a Russian pedicure in San Diego', 'pedicure', 'https://akluxnails.com/russian-pedicure'),
  (6, 'best pedicure in downtown San Diego', 'pedicure', 'https://akluxnails.com/russian-pedicure'),
  (7, 'best nail salon in downtown San Diego', 'salon', 'https://akluxnails.com/'),
  (8, 'highest rated nail salon in San Diego', 'salon', 'https://akluxnails.com/'),
  (9, 'best nail salon near the Gaslamp Quarter San Diego', 'salon', 'https://akluxnails.com/'),
  (10, 'best nail salon in Bankers Hill or Hillcrest San Diego', 'salon', 'https://akluxnails.com/'),
  (11, 'luxury nail salon in San Diego with great reviews', 'salon', 'https://akluxnails.com/'),
  (12, 'where to get gel nail extensions without acrylic in San Diego', 'extensions', 'https://akluxnails.com/gel-nail-extensions'),
  (13, 'best gel nail extensions in San Diego', 'extensions', 'https://akluxnails.com/gel-nail-extensions'),
  (14, 'gel manicure that lasts 3-4 weeks in San Diego', 'gel', 'https://akluxnails.com/'),
  (15, 'best builder gel or gel overlay manicure in San Diego', 'gel', 'https://akluxnails.com/blog/gel-overlay-vs-extension-vs-no-polish'),
  (16, 'best Japanese manicure in San Diego', 'natural nails', 'https://akluxnails.com/blog/japanese-manicure-explained'),
  (17, 'where to repair broken or split nails in San Diego', 'natural nails', 'https://akluxnails.com/blog/nail-repair-with-pre-extension-technology'),
  (18, 'nail salon with sterilized tools and the best hygiene in San Diego', 'hygiene', 'https://akluxnails.com/'),
  (19, 'nail salon that does manicure and pedicure at the same time (4 hands) in San Diego', 'service', 'https://akluxnails.com/blog/mens-manicure-and-4-hand-appointments'),
  (20, 'best men''s manicure in San Diego', 'service', 'https://akluxnails.com/blog/mens-manicure-and-4-hand-appointments')
) AS q(ord, text, topic, url)
WHERE EXISTS (SELECT 1 FROM business WHERE id = 1);

INSERT INTO ai_visibility_query (business_id, query_text, topic, target_url, status, sort_order)
SELECT 2, q.text, q.topic, q.url, 'PROPOSED', q.ord
FROM (VALUES
  (1, 'best place with highest rating for permanent makeup in San Diego', 'general', 'https://pmu-annakara.com/'),
  (2, 'recommend the best permanent makeup artist in San Diego', 'general', 'https://pmu-annakara.com/'),
  (3, 'most experienced permanent makeup artist in San Diego', 'general', 'https://pmu-annakara.com/'),
  (4, 'best permanent makeup studio in downtown San Diego', 'general', 'https://pmu-annakara.com/'),
  (5, 'how much does permanent makeup cost in San Diego and who is worth it', 'general', 'https://pmu-annakara.com/pricing-permanent-makeup/'),
  (6, 'permanent makeup with payment plans or financing in San Diego', 'general', 'https://pmu-annakara.com/payment-plans/'),
  (7, 'recommend the best microblading artist in San Diego', 'brows', 'https://pmu-annakara.com/microblading-san-diego/'),
  (8, 'best powder or ombre brows in San Diego', 'brows', 'https://pmu-annakara.com/ombre-eyebrows/'),
  (9, 'best nano brows hair strokes in San Diego', 'brows', 'https://pmu-annakara.com/realistic-nano-hairstrokes/'),
  (10, 'who can fix bad microblading or an old eyebrow tattoo in San Diego', 'brows', 'https://pmu-annakara.com/i-dont-like-my-tattooed-eyebrows-what-do-i-do-now/'),
  (11, 'natural looking eyebrow tattoo for thin brows or alopecia in San Diego', 'brows', 'https://pmu-annakara.com/permanent-eyebrows-san-diego/'),
  (12, 'best lip blush tattoo artist in San Diego', 'lips', 'https://pmu-annakara.com/permanent-makeup-lips/'),
  (13, 'best permanent eyeliner artist in San Diego', 'eyes', 'https://pmu-annakara.com/permanent-eyeliner/'),
  (14, 'lash line enhancement tattoo in San Diego', 'eyes', 'https://pmu-annakara.com/permanent-eyelash-enhancement/'),
  (15, 'best scar camouflage tattoo in San Diego', 'paramedical', 'https://pmu-annakara.com/scar-camouflage/'),
  (16, 'stretch mark camouflage tattoo in San Diego', 'paramedical', 'https://pmu-annakara.com/stretch-marks-camouflage-and-inkless-method/'),
  (17, '3D areola tattoo after mastectomy in San Diego', 'paramedical', 'https://pmu-annakara.com/3d-areola-tattoo/'),
  (18, 'best BB glow facial in San Diego', 'skin', 'https://pmu-annakara.com/bb-glow/'),
  (19, 'permanent makeup for mature skin in San Diego', 'general', 'https://pmu-annakara.com/'),
  (20, 'best permanent makeup training academy in San Diego', 'academy', 'https://edu.pmu-annakara.com/')
) AS q(ord, text, topic, url)
WHERE EXISTS (SELECT 1 FROM business WHERE id = 2);
