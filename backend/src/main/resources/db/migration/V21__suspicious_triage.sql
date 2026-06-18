-- One row per LLM-generated triage of a suspicious booking. Keyed by booking + prompt version so
-- a prompt-engineering change doesn't lose history; repeat clicks under the same prompt version
-- return the cached row and never re-call the LLM. Owner feedback (helpful / corrected) is written
-- back to the same row by the feedback endpoint and shipped to LangSmith as a graded run.
CREATE TABLE suspicious_triage (
    id                          BIGSERIAL PRIMARY KEY,
    square_booking_id           VARCHAR(255) NOT NULL,
    prompt_version              VARCHAR(32)  NOT NULL,
    classification              VARCHAR(32)  NOT NULL,
    confidence                  NUMERIC(4,3) NOT NULL,
    explanation                 TEXT         NOT NULL,
    draft_message               TEXT         NOT NULL,
    signals_json                JSONB        NOT NULL,
    model                       VARCHAR(64)  NOT NULL,
    langsmith_run_id            VARCHAR(64),
    refusal_category            VARCHAR(64),
    helpful                     BOOLEAN,
    corrected_classification    VARCHAR(32),
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_suspicious_triage_booking_prompt UNIQUE (square_booking_id, prompt_version)
);

CREATE INDEX idx_suspicious_triage_created_at
    ON suspicious_triage (created_at DESC);
