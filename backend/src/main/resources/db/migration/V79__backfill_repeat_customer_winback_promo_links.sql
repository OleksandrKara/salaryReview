-- One-time backfill: repeat_customer_winback's booking links were sent today (2026-08-09, its
-- first-ever run) before this deploy added the $5-off/$99-minimum WINBACK5 promo to them — they
-- carried the plain BOOK_NOW target instead. The short link itself (/r/<token>) never changes;
-- ShortLinkController resolves it dynamically from this row's link_target every time it's
-- clicked, so rewriting it here is enough to make the very same already-sent link start applying
-- the discount on the next click, for anyone who clicks it again or hasn't clicked it yet — no
-- retroactive effect on anyone who already completed a booking without the discount, and no new
-- Square-side enrollment happens until (and unless) the link is actually clicked and a booking
-- is completed through it. Expiry is computed the same way the live scheduler does (end of the
-- Pacific-time calendar day the message was actually sent on), not "end of today" at migration
-- time, so this stays correct regardless of exactly when this deploy lands.
UPDATE sms_message
SET link_target = 'WINBACK:' || extract(epoch from (
    (date_trunc('day', created_at AT TIME ZONE 'America/Los_Angeles') + interval '1 day')
    AT TIME ZONE 'America/Los_Angeles'
))::bigint
WHERE automation_key = 'repeat_customer_winback'
  AND link_target = 'BOOK_NOW';
