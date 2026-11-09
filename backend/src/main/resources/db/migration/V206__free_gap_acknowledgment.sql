-- Date the worker signed the "free time between appointments" acknowledgment. Evidence only, never changes pay.
ALTER TABLE providers ADD COLUMN free_gap_ack_signed_at DATE;
