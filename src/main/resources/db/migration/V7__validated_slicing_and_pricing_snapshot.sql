ALTER TABLE model_3d ADD COLUMN validated_material_consumed_grams NUMERIC(18, 6);
ALTER TABLE model_3d ADD COLUMN validated_print_time_seconds BIGINT;
ALTER TABLE model_3d ADD COLUMN slicing_data_source VARCHAR(100);
ALTER TABLE model_3d ADD COLUMN slicing_data_validated_at TIMESTAMP;

ALTER TABLE price_estimations ADD COLUMN material_cost_vnd NUMERIC(24, 10);
ALTER TABLE price_estimations ADD COLUMN machine_cost_vnd NUMERIC(24, 10);
ALTER TABLE price_estimations ADD COLUMN electricity_cost_vnd NUMERIC(24, 10);
ALTER TABLE price_estimations ADD COLUMN production_cost_vnd NUMERIC(24, 10);
ALTER TABLE price_estimations ADD COLUMN failure_adjusted_cost_vnd NUMERIC(24, 10);
ALTER TABLE price_estimations ADD COLUMN total_cost_vnd NUMERIC(24, 10);
ALTER TABLE price_estimations ADD COLUMN slicing_data_source VARCHAR(100);

-- V6 already adds total_price_vnd; the entity maps that server-calculated integer amount.
