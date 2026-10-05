-- PMU pre-consultation emails (owner request 2026-10-05): business 2's clients often wait days or
-- weeks between booking a consultation and having it, so the sequence grows two middle steps for
-- long waits: "meet your artist" (~2 days after booking, only when the visit is 4+ days out) and
-- "getting ready" (~3 days before, only when the visit was booked 8+ days out). visit_kind records
-- which template set a booking uses (NULL = the original generic welcome/reminder pair), decided
-- once at welcome time from the booked Square service.
ALTER TABLE pre_visit_nurture_send
    ADD COLUMN visit_kind        TEXT,
    ADD COLUMN meet_artist_state TEXT CHECK (meet_artist_state IN ('SENT', 'SKIPPED_DISABLED', 'SKIPPED_NO_EMAIL',
                                                                   'SKIPPED_NOT_CONFIGURED', 'SKIPPED_NO_TEMPLATE',
                                                                   'SKIPPED_CANCELLED', 'SEND_FAILED')),
    ADD COLUMN prep_state        TEXT CHECK (prep_state IN ('SENT', 'SKIPPED_DISABLED', 'SKIPPED_NO_EMAIL',
                                                            'SKIPPED_NOT_CONFIGURED', 'SKIPPED_NO_TEMPLATE',
                                                            'SKIPPED_CANCELLED', 'SEND_FAILED'));

-- Rows from before this migration were all considered under the generic pair only; mark the new
-- steps as not applicable so the new pollers never pick up an old booking.
UPDATE pre_visit_nurture_send SET meet_artist_state = 'SKIPPED_NO_TEMPLATE', prep_state = 'SKIPPED_NO_TEMPLATE';
