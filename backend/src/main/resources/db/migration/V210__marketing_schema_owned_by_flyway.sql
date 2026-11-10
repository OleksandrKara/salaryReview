-- Ownership of the marketing schema moves from the retired salonLandings (alembic) into Flyway.
-- Idempotent: production already has every object below, so this is a no-op there; a fresh database
-- gets the schema the landing endpoints (LandingWriteRepository, LandingReadRepository) expect.
CREATE SCHEMA IF NOT EXISTS marketing;

CREATE TABLE IF NOT EXISTS marketing.abuse_blocks (
    id bigint NOT NULL,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    endpoint text NOT NULL,
    reason text NOT NULL,
    phone_number text,
    ip_address text,
    business_id bigint NOT NULL
);

CREATE SEQUENCE IF NOT EXISTS marketing.abuse_blocks_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE marketing.abuse_blocks_id_seq OWNED BY marketing.abuse_blocks.id;

CREATE TABLE IF NOT EXISTS marketing.attribution (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    booking_id text NOT NULL,
    landing_page_id uuid,
    variant_id uuid,
    source text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    business_id bigint NOT NULL
);

CREATE TABLE IF NOT EXISTS marketing.contacts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    phone_number text NOT NULL,
    given_name text,
    email_address text,
    original_traffic_source text,
    marketing_traffic_source text,
    utm_source text,
    utm_medium text,
    utm_campaign text,
    referrer text,
    sms_marketing_consent boolean,
    email_marketing_consent boolean,
    square_customer_id text,
    square_booking_id text,
    booking_status text,
    booking_start_at timestamp with time zone,
    booking_service_name text,
    booking_price numeric(10,2),
    booking_artist_name text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    landing_page_slug text,
    variant_name text,
    device_type text,
    os_name text,
    os_version text,
    browser_name text,
    browser_version text,
    business_id bigint NOT NULL
);

CREATE TABLE IF NOT EXISTS marketing.email_consent (
    id bigint NOT NULL,
    email_address text NOT NULL,
    consented boolean NOT NULL,
    consent_text text NOT NULL,
    consent_version text NOT NULL,
    source text NOT NULL,
    visitor_id uuid,
    ip_address text,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    business_id bigint NOT NULL
);

CREATE SEQUENCE IF NOT EXISTS marketing.email_consent_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE marketing.email_consent_id_seq OWNED BY marketing.email_consent.id;

CREATE TABLE IF NOT EXISTS marketing.events (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    session_id uuid NOT NULL,
    landing_page_id uuid,
    variant_id uuid,
    event_type text NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    business_id bigint NOT NULL,
    CONSTRAINT events_event_type_check CHECK ((event_type = ANY (ARRAY['page_view'::text, 'click'::text, 'booking_started'::text, 'booking_completed'::text])))
);

CREATE TABLE IF NOT EXISTS marketing.experiments (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    landing_page_id uuid NOT NULL,
    status text DEFAULT 'paused'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    business_id bigint NOT NULL,
    CONSTRAINT experiments_status_check CHECK ((status = ANY (ARRAY['active'::text, 'paused'::text])))
);

CREATE TABLE IF NOT EXISTS marketing.funnel_events (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    session_id uuid NOT NULL,
    landing_page_id uuid,
    variant_id uuid,
    flow_key text NOT NULL,
    step_key text NOT NULL,
    step_index integer NOT NULL,
    step_count_total integer NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    business_id bigint NOT NULL
);

CREATE TABLE IF NOT EXISTS marketing.landing_pages (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name text NOT NULL,
    slug text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    stats_since timestamp with time zone,
    business_id bigint NOT NULL
);

CREATE TABLE IF NOT EXISTS marketing.landing_variants (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    landing_page_id uuid NOT NULL,
    name text NOT NULL,
    weight integer DEFAULT 20 NOT NULL,
    content jsonb DEFAULT '{}'::jsonb NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    key text,
    description text,
    business_id bigint NOT NULL
);

CREATE TABLE IF NOT EXISTS marketing.sms_consent (
    id bigint NOT NULL,
    phone_number text NOT NULL,
    consented boolean NOT NULL,
    consent_text text NOT NULL,
    consent_version text NOT NULL,
    source text NOT NULL,
    visitor_id uuid,
    ip_address text,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    business_id bigint NOT NULL
);

CREATE SEQUENCE IF NOT EXISTS marketing.sms_consent_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE marketing.sms_consent_id_seq OWNED BY marketing.sms_consent.id;

CREATE TABLE IF NOT EXISTS marketing.submissions (
    id bigint NOT NULL,
    visitor_id uuid,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    submission_type text NOT NULL,
    square_booking_id text,
    service_name text,
    price numeric(10,2),
    customer_email text,
    customer_phone text,
    landing_path text,
    referrer text,
    utm_source text,
    utm_medium text,
    utm_campaign text,
    utm_term text,
    utm_content text,
    fbclid text,
    gclid text,
    user_agent text,
    device_type text,
    os_name text,
    os_version text,
    browser_name text,
    browser_version text,
    ip_address text,
    landing_page_slug text,
    variant_name text,
    traffic_source text,
    business_id bigint NOT NULL
);

CREATE SEQUENCE IF NOT EXISTS marketing.submissions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE marketing.submissions_id_seq OWNED BY marketing.submissions.id;

CREATE TABLE IF NOT EXISTS marketing.visits (
    id bigint NOT NULL,
    visitor_id uuid NOT NULL,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    landing_path text,
    referrer text,
    utm_source text,
    utm_medium text,
    utm_campaign text,
    utm_term text,
    utm_content text,
    fbclid text,
    gclid text,
    user_agent text,
    device_type text,
    os_name text,
    os_version text,
    browser_name text,
    browser_version text,
    ip_address text,
    business_id bigint NOT NULL
);

CREATE SEQUENCE IF NOT EXISTS marketing.visits_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE marketing.visits_id_seq OWNED BY marketing.visits.id;

ALTER TABLE ONLY marketing.abuse_blocks ALTER COLUMN id SET DEFAULT nextval('marketing.abuse_blocks_id_seq'::regclass);

ALTER TABLE ONLY marketing.email_consent ALTER COLUMN id SET DEFAULT nextval('marketing.email_consent_id_seq'::regclass);

ALTER TABLE ONLY marketing.sms_consent ALTER COLUMN id SET DEFAULT nextval('marketing.sms_consent_id_seq'::regclass);

ALTER TABLE ONLY marketing.submissions ALTER COLUMN id SET DEFAULT nextval('marketing.submissions_id_seq'::regclass);

ALTER TABLE ONLY marketing.visits ALTER COLUMN id SET DEFAULT nextval('marketing.visits_id_seq'::regclass);

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'abuse_blocks_pkey' AND conrelid = 'marketing.abuse_blocks'::regclass) THEN
    ALTER TABLE ONLY marketing.abuse_blocks     ADD CONSTRAINT abuse_blocks_pkey PRIMARY KEY (id);
  END IF;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'attribution_pkey' AND conrelid = 'marketing.attribution'::regclass) THEN
    ALTER TABLE ONLY marketing.attribution     ADD CONSTRAINT attribution_pkey PRIMARY KEY (id);
  END IF;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'contacts_pkey' AND conrelid = 'marketing.contacts'::regclass) THEN
    ALTER TABLE ONLY marketing.contacts     ADD CONSTRAINT contacts_pkey PRIMARY KEY (id);
  END IF;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'email_consent_pkey' AND conrelid = 'marketing.email_consent'::regclass) THEN
    ALTER TABLE ONLY marketing.email_consent     ADD CONSTRAINT email_consent_pkey PRIMARY KEY (id);
  END IF;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'events_pkey' AND conrelid = 'marketing.events'::regclass) THEN
    ALTER TABLE ONLY marketing.events     ADD CONSTRAINT events_pkey PRIMARY KEY (id);
  END IF;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'experiments_pkey' AND conrelid = 'marketing.experiments'::regclass) THEN
    ALTER TABLE ONLY marketing.experiments     ADD CONSTRAINT experiments_pkey PRIMARY KEY (id);
  END IF;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'funnel_events_pkey' AND conrelid = 'marketing.funnel_events'::regclass) THEN
    ALTER TABLE ONLY marketing.funnel_events     ADD CONSTRAINT funnel_events_pkey PRIMARY KEY (id);
  END IF;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'landing_pages_pkey' AND conrelid = 'marketing.landing_pages'::regclass) THEN
    ALTER TABLE ONLY marketing.landing_pages     ADD CONSTRAINT landing_pages_pkey PRIMARY KEY (id);
  END IF;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'landing_variants_pkey' AND conrelid = 'marketing.landing_variants'::regclass) THEN
    ALTER TABLE ONLY marketing.landing_variants     ADD CONSTRAINT landing_variants_pkey PRIMARY KEY (id);
  END IF;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'sms_consent_pkey' AND conrelid = 'marketing.sms_consent'::regclass) THEN
    ALTER TABLE ONLY marketing.sms_consent     ADD CONSTRAINT sms_consent_pkey PRIMARY KEY (id);
  END IF;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'submissions_pkey' AND conrelid = 'marketing.submissions'::regclass) THEN
    ALTER TABLE ONLY marketing.submissions     ADD CONSTRAINT submissions_pkey PRIMARY KEY (id);
  END IF;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'visits_pkey' AND conrelid = 'marketing.visits'::regclass) THEN
    ALTER TABLE ONLY marketing.visits     ADD CONSTRAINT visits_pkey PRIMARY KEY (id);
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_marketing_abuse_blocks_business_id ON marketing.abuse_blocks USING btree (business_id);

CREATE INDEX IF NOT EXISTS idx_marketing_abuse_blocks_occurred_at ON marketing.abuse_blocks USING btree (occurred_at);

CREATE INDEX IF NOT EXISTS idx_marketing_attribution_business_id ON marketing.attribution USING btree (business_id);

CREATE INDEX IF NOT EXISTS idx_marketing_attribution_variant ON marketing.attribution USING btree (variant_id);

CREATE INDEX IF NOT EXISTS idx_marketing_contacts_business_id ON marketing.contacts USING btree (business_id);

CREATE INDEX IF NOT EXISTS idx_marketing_contacts_created_at ON marketing.contacts USING btree (created_at);

CREATE INDEX IF NOT EXISTS idx_marketing_contacts_square_customer ON marketing.contacts USING btree (square_customer_id);

CREATE INDEX IF NOT EXISTS idx_marketing_email_consent_business_id ON marketing.email_consent USING btree (business_id);

CREATE INDEX IF NOT EXISTS idx_marketing_email_consent_email ON marketing.email_consent USING btree (email_address);

CREATE INDEX IF NOT EXISTS idx_marketing_email_consent_occurred_at ON marketing.email_consent USING btree (occurred_at);

CREATE INDEX IF NOT EXISTS idx_marketing_events_business_id ON marketing.events USING btree (business_id);

CREATE INDEX IF NOT EXISTS idx_marketing_events_created_at ON marketing.events USING btree (created_at);

CREATE INDEX IF NOT EXISTS idx_marketing_events_session ON marketing.events USING btree (session_id);

CREATE INDEX IF NOT EXISTS idx_marketing_events_type ON marketing.events USING btree (event_type);

CREATE INDEX IF NOT EXISTS idx_marketing_events_variant ON marketing.events USING btree (variant_id);

CREATE INDEX IF NOT EXISTS idx_marketing_experiments_business_id ON marketing.experiments USING btree (business_id);

CREATE INDEX IF NOT EXISTS idx_marketing_experiments_page ON marketing.experiments USING btree (landing_page_id);

CREATE INDEX IF NOT EXISTS idx_marketing_funnel_events_business_id ON marketing.funnel_events USING btree (business_id);

CREATE INDEX IF NOT EXISTS idx_marketing_funnel_events_created_at ON marketing.funnel_events USING btree (created_at);

CREATE INDEX IF NOT EXISTS idx_marketing_funnel_events_flow_step ON marketing.funnel_events USING btree (flow_key, step_index);

CREATE INDEX IF NOT EXISTS idx_marketing_funnel_events_session ON marketing.funnel_events USING btree (session_id);

CREATE INDEX IF NOT EXISTS idx_marketing_funnel_events_variant ON marketing.funnel_events USING btree (variant_id);

CREATE INDEX IF NOT EXISTS idx_marketing_landing_pages_business_id ON marketing.landing_pages USING btree (business_id);

CREATE INDEX IF NOT EXISTS idx_marketing_landing_variants_business_id ON marketing.landing_variants USING btree (business_id);

CREATE INDEX IF NOT EXISTS idx_marketing_landing_variants_page ON marketing.landing_variants USING btree (landing_page_id);

CREATE INDEX IF NOT EXISTS idx_marketing_sms_consent_business_id ON marketing.sms_consent USING btree (business_id);

CREATE INDEX IF NOT EXISTS idx_marketing_sms_consent_occurred_at ON marketing.sms_consent USING btree (occurred_at);

CREATE INDEX IF NOT EXISTS idx_marketing_sms_consent_phone ON marketing.sms_consent USING btree (phone_number);

CREATE INDEX IF NOT EXISTS idx_marketing_submissions_business_id ON marketing.submissions USING btree (business_id);

CREATE INDEX IF NOT EXISTS idx_marketing_submissions_customer_email ON marketing.submissions USING btree (customer_email);

CREATE INDEX IF NOT EXISTS idx_marketing_submissions_customer_phone ON marketing.submissions USING btree (customer_phone);

CREATE INDEX IF NOT EXISTS idx_marketing_submissions_occurred_at ON marketing.submissions USING btree (occurred_at);

CREATE INDEX IF NOT EXISTS idx_marketing_submissions_type ON marketing.submissions USING btree (submission_type);

CREATE INDEX IF NOT EXISTS idx_marketing_submissions_visitor_id ON marketing.submissions USING btree (visitor_id);

CREATE INDEX IF NOT EXISTS idx_marketing_visits_business_id ON marketing.visits USING btree (business_id);

CREATE INDEX IF NOT EXISTS idx_marketing_visits_occurred_at ON marketing.visits USING btree (occurred_at);

CREATE INDEX IF NOT EXISTS idx_marketing_visits_utm_source ON marketing.visits USING btree (utm_source);

CREATE INDEX IF NOT EXISTS idx_marketing_visits_visitor_id ON marketing.visits USING btree (visitor_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_marketing_attribution_booking_id ON marketing.attribution USING btree (booking_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_marketing_contacts_business_phone ON marketing.contacts USING btree (business_id, phone_number);

CREATE UNIQUE INDEX IF NOT EXISTS uq_marketing_experiments_active_page ON marketing.experiments USING btree (landing_page_id) WHERE (status = 'active'::text);

CREATE UNIQUE INDEX IF NOT EXISTS uq_marketing_landing_pages_business_slug ON marketing.landing_pages USING btree (business_id, slug);

CREATE UNIQUE INDEX IF NOT EXISTS uq_marketing_landing_variants_page_key ON marketing.landing_variants USING btree (landing_page_id, key) WHERE (key IS NOT NULL);

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'attribution_landing_page_id_fkey' AND conrelid = 'marketing.attribution'::regclass) THEN
    ALTER TABLE ONLY marketing.attribution     ADD CONSTRAINT attribution_landing_page_id_fkey FOREIGN KEY (landing_page_id) REFERENCES marketing.landing_pages(id);
  END IF;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'attribution_variant_id_fkey' AND conrelid = 'marketing.attribution'::regclass) THEN
    ALTER TABLE ONLY marketing.attribution     ADD CONSTRAINT attribution_variant_id_fkey FOREIGN KEY (variant_id) REFERENCES marketing.landing_variants(id);
  END IF;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'events_landing_page_id_fkey' AND conrelid = 'marketing.events'::regclass) THEN
    ALTER TABLE ONLY marketing.events     ADD CONSTRAINT events_landing_page_id_fkey FOREIGN KEY (landing_page_id) REFERENCES marketing.landing_pages(id);
  END IF;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'events_variant_id_fkey' AND conrelid = 'marketing.events'::regclass) THEN
    ALTER TABLE ONLY marketing.events     ADD CONSTRAINT events_variant_id_fkey FOREIGN KEY (variant_id) REFERENCES marketing.landing_variants(id);
  END IF;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'experiments_landing_page_id_fkey' AND conrelid = 'marketing.experiments'::regclass) THEN
    ALTER TABLE ONLY marketing.experiments     ADD CONSTRAINT experiments_landing_page_id_fkey FOREIGN KEY (landing_page_id) REFERENCES marketing.landing_pages(id);
  END IF;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'funnel_events_landing_page_id_fkey' AND conrelid = 'marketing.funnel_events'::regclass) THEN
    ALTER TABLE ONLY marketing.funnel_events     ADD CONSTRAINT funnel_events_landing_page_id_fkey FOREIGN KEY (landing_page_id) REFERENCES marketing.landing_pages(id);
  END IF;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'funnel_events_variant_id_fkey' AND conrelid = 'marketing.funnel_events'::regclass) THEN
    ALTER TABLE ONLY marketing.funnel_events     ADD CONSTRAINT funnel_events_variant_id_fkey FOREIGN KEY (variant_id) REFERENCES marketing.landing_variants(id);
  END IF;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'landing_variants_landing_page_id_fkey' AND conrelid = 'marketing.landing_variants'::regclass) THEN
    ALTER TABLE ONLY marketing.landing_variants     ADD CONSTRAINT landing_variants_landing_page_id_fkey FOREIGN KEY (landing_page_id) REFERENCES marketing.landing_pages(id);
  END IF;
END $$;
