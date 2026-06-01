-- Square customers who are the salon's owner(s)/family. When one of them receives a service, no
-- payment is taken (the owner isn't charged), so Square has no order for it — but the provider who
-- did the work is still owed their commission. Bookings for these customers with no matching order
-- are credited to the provider at the catalog menu price ("owner comp"); see SquareMonthAggregator.
CREATE TABLE owner_customer (
    id                 BIGSERIAL PRIMARY KEY,
    square_customer_id VARCHAR(64)  NOT NULL UNIQUE,
    label              VARCHAR(255),               -- display name, for the admin list
    created_by         VARCHAR(100),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
