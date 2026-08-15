ALTER TABLE salon_config DROP CONSTRAINT salon_config_id_check;

ALTER TABLE salon_config ADD COLUMN business_id BIGINT REFERENCES business(id);

UPDATE salon_config
SET business_id = (SELECT id FROM business WHERE short_code = 'akluxnails')
WHERE id = 1;

ALTER TABLE salon_config ALTER COLUMN business_id SET NOT NULL;
ALTER TABLE salon_config ADD CONSTRAINT salon_config_business_id_uq UNIQUE (business_id);
