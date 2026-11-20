DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM cart_items
        GROUP BY cart_id, model_id, estimate_id
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'Duplicate cart selections exist; reconcile cart_items before applying V5';
    END IF;
END
$$;

CREATE UNIQUE INDEX uq_cart_items_selection
    ON cart_items (cart_id, model_id, estimate_id);
