-- Backfills existing phone_number columns to E.164 across every table this app owns that stores
-- one. The same customer's number previously arrived in different shapes depending on its source
-- (Twilio's inbound webhook always sends E.164, but Square's own Customer.phoneNumber() and
-- marketing.contacts — written by the separate salonLandings service — carry whatever format a
-- customer originally typed, e.g. "(310) 779-6334"), which silently split one customer's texts
-- into two "different" conversations on the Messages page that never merged. New writes are now
-- normalized at the source (see com.salonreview.util.PhoneNumbers); this migration brings existing
-- rows in line with that same rule so historical conversations merge too.
--
-- Mirrors com.salonreview.util.PhoneNumbers#normalize exactly: a 10-digit number gets a "+1"
-- prefix, an 11-digit number already starting with "1" just gets a "+" prefix, anything else is
-- left untouched rather than guessed at.
CREATE OR REPLACE FUNCTION normalize_phone_v56(raw text) RETURNS text AS $$
DECLARE
    digits text := regexp_replace(raw, '[^0-9]', '', 'g');
BEGIN
    IF length(digits) = 10 THEN
        RETURN '+1' || digits;
    ELSIF length(digits) = 11 AND left(digits, 1) = '1' THEN
        RETURN '+' || digits;
    ELSE
        RETURN raw;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- marketing_contact_square_link.phone_number is unique — dedupe any rows that would collide once
-- normalized (e.g. one saved as "(310) 779-6334", another as "+13107796334" for the same real
-- number) before the UPDATE below, keeping the one synced most recently.
DELETE FROM marketing_contact_square_link a
USING marketing_contact_square_link b
WHERE a.id < b.id
  AND normalize_phone_v56(a.phone_number) = normalize_phone_v56(b.phone_number);

UPDATE sms_message SET phone_number = normalize_phone_v56(phone_number);
UPDATE sms_reply_flow SET phone_number = normalize_phone_v56(phone_number);
UPDATE same_day_rebooking_send SET phone_number = normalize_phone_v56(phone_number);
UPDATE marketing_contact_square_link SET phone_number = normalize_phone_v56(phone_number);

DROP FUNCTION normalize_phone_v56(text);
