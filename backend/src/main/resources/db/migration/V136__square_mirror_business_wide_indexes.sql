-- Phase 2: business-wide (not customer-scoped) date-range reads of the Square mirror — needed by
-- SquareMonthAggregator, which sweeps a whole month's bookings/orders/payments at once rather than
-- one customer at a time. square_order already has (business_id, closed_at) from V134; only
-- square_booking (indexed only on updated_at) and square_payment (indexed only on customer/order
-- id) are missing their own date-range index.
CREATE INDEX square_booking_business_start_idx ON square_booking(business_id, start_at);
CREATE INDEX square_payment_business_created_idx ON square_payment(business_id, created_at);
