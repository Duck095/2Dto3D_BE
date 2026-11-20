ALTER TABLE price_estimations ADD COLUMN total_price_vnd BIGINT;

ALTER TABLE orders ADD COLUMN total_amount_vnd BIGINT;

ALTER TABLE order_items ADD COLUMN price_snapshot_vnd BIGINT;

ALTER TABLE payments ADD COLUMN amount_vnd BIGINT;
