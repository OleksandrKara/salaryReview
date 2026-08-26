ALTER TABLE business
    ADD COLUMN yelp_review_url TEXT;

-- Owner-supplied values for both current businesses — see CheckoutReviewLinks/
-- CheckoutReviewTriggerService for how this slots into the checkout_review_request escalation
-- ladder (Google review -> Yelp review -> private feedback form).
UPDATE business
SET yelp_review_url = 'https://www.yelp.com/writeareview/biz/NIeTADFFBIkBihfJ7xN2hQ'
WHERE short_code = 'akluxnails';

UPDATE business
SET yelp_review_url = 'https://www.yelp.com/writeareview/biz/7lHUQPPNrs9ya6WdiWn2IQ'
WHERE short_code = 'annakarapmu';
