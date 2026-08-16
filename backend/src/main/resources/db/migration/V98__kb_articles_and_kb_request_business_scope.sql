-- kb_articles and kb_request predate multi-tenancy and have zero tenant boundary (tasks.md 2.6).
-- Both are root tables with no existing FK into an already business-scoped table (unlike
-- staff_documents/sop_versions/sop_acknowledgments, which could filter via a join instead), so both
-- need a real business_id column here.
ALTER TABLE kb_articles ADD COLUMN business_id BIGINT;
UPDATE kb_articles SET business_id = (SELECT id FROM business WHERE short_code = 'akluxnails');
ALTER TABLE kb_articles ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE kb_articles ADD CONSTRAINT kb_articles_business_id_fkey FOREIGN KEY (business_id) REFERENCES business (id);
CREATE INDEX idx_kb_articles_business_id ON kb_articles (business_id);

ALTER TABLE kb_request ADD COLUMN business_id BIGINT;
UPDATE kb_request SET business_id = (SELECT id FROM business WHERE short_code = 'akluxnails');
ALTER TABLE kb_request ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE kb_request ADD CONSTRAINT kb_request_business_id_fkey FOREIGN KEY (business_id) REFERENCES business (id);
CREATE INDEX idx_kb_request_business_id ON kb_request (business_id);
