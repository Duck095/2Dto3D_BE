-- Narrow SECURITY DEFINER entry points for Admin 3D row locks.
-- Role admin3d_lock_owner is provisioned outside Flyway and is never a runtime member.

GRANT SELECT ON TABLE public.model_3d, public.media_asset, public.materials TO admin3d_lock_owner;
GRANT UPDATE (model_id) ON TABLE public.model_3d TO admin3d_lock_owner;
GRANT UPDATE (id) ON TABLE public.media_asset TO admin3d_lock_owner;
GRANT UPDATE (material_id) ON TABLE public.materials TO admin3d_lock_owner;
GRANT USAGE, CREATE ON SCHEMA public TO admin3d_lock_owner;

SET ROLE admin3d_lock_owner;

CREATE FUNCTION public.admin3d_lock_product(p_product_id integer)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
SET lock_timeout = '1500ms'
AS $$
BEGIN
    IF p_product_id IS NULL OR p_product_id <= 0 THEN
        RAISE EXCEPTION 'Invalid product identifier' USING ERRCODE = '22023';
    END IF;

    PERFORM model.model_id
    FROM public.model_3d AS model
    WHERE model.model_id = p_product_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Product row was not found' USING ERRCODE = 'P0002';
    END IF;
END;
$$;

CREATE FUNCTION public.admin3d_lock_media_operation(p_operation_id uuid)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
SET lock_timeout = '1500ms'
AS $$
DECLARE
    locked_asset_id bigint;
    locked_count integer := 0;
BEGIN
    IF p_operation_id IS NULL THEN
        RAISE EXCEPTION 'Invalid media operation identifier' USING ERRCODE = '22023';
    END IF;

    FOR locked_asset_id IN
        SELECT asset.id
        FROM public.media_asset AS asset
        WHERE asset.operation_id = p_operation_id
        ORDER BY asset.id ASC
        FOR UPDATE
    LOOP
        locked_count := locked_count + 1;
    END LOOP;

    IF locked_count = 0 THEN
        RAISE EXCEPTION 'Media operation has no assets' USING ERRCODE = 'P0002';
    END IF;
    RETURN locked_count;
END;
$$;

CREATE FUNCTION public.admin3d_lock_material(p_material_id integer)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
SET lock_timeout = '1500ms'
AS $$
BEGIN
    IF p_material_id IS NULL OR p_material_id <= 0 THEN
        RAISE EXCEPTION 'Invalid material identifier' USING ERRCODE = '22023';
    END IF;

    PERFORM material.material_id
    FROM public.materials AS material
    WHERE material.material_id = p_material_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Material row was not found' USING ERRCODE = 'P0002';
    END IF;
END;
$$;

REVOKE ALL ON FUNCTION public.admin3d_lock_product(integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.admin3d_lock_media_operation(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.admin3d_lock_material(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.admin3d_lock_product(integer) TO app_runtime;
GRANT EXECUTE ON FUNCTION public.admin3d_lock_media_operation(uuid) TO app_runtime;
GRANT EXECUTE ON FUNCTION public.admin3d_lock_material(integer) TO app_runtime;

RESET ROLE;
REVOKE CREATE ON SCHEMA public FROM admin3d_lock_owner;
