-- Restricted Admin 3D commands. The NOLOGIN owner and its membership are provisioned out of band.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'admin3d_command_owner') THEN
        RAISE EXCEPTION 'Required role admin3d_command_owner is not provisioned' USING ERRCODE = '55000';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'app_runtime') THEN
        RAISE EXCEPTION 'Required role app_runtime is not provisioned' USING ERRCODE = '55000';
    END IF;
    IF (SELECT rolcanlogin OR rolinherit OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls
        FROM pg_catalog.pg_roles WHERE rolname = 'admin3d_command_owner') THEN
        RAISE EXCEPTION 'admin3d_command_owner must be a restricted NOLOGIN NOINHERIT role' USING ERRCODE = '55000';
    END IF;
    IF pg_catalog.pg_has_role('app_runtime', 'admin3d_command_owner', 'MEMBER') THEN
        RAISE EXCEPTION 'app_runtime must not be a member of admin3d_command_owner' USING ERRCODE = '55000';
    END IF;
END;
$$;

GRANT SELECT ON TABLE public.model_3d, public.model_images, public.media_asset
    TO admin3d_command_owner;
GRANT UPDATE (name, description, category, model_status) ON TABLE public.model_3d
    TO admin3d_command_owner;
-- PostgreSQL row locking requires UPDATE privilege; this owner is NOLOGIN and is not a runtime member.
GRANT UPDATE (image_id) ON TABLE public.model_images TO admin3d_command_owner;

-- V16 lock functions belong to admin3d_lock_owner, so only that owner can grant their use.
SET ROLE admin3d_lock_owner;
GRANT EXECUTE ON FUNCTION public.admin3d_lock_product(integer) TO admin3d_command_owner;
GRANT EXECUTE ON FUNCTION public.admin3d_lock_media_operation(uuid) TO admin3d_command_owner;
RESET ROLE;

GRANT USAGE, CREATE ON SCHEMA public TO admin3d_command_owner;

SET ROLE admin3d_command_owner;

CREATE TABLE public.admin3d_publication_policy (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    cloud_name VARCHAR(255) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_admin3d_publication_cloud_name
        CHECK (cloud_name = pg_catalog.btrim(cloud_name) AND cloud_name <> '')
);

CREATE FUNCTION public.admin3d_publish_designer_model(p_product_id INTEGER)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
SET lock_timeout = '1500ms'
AS $$
DECLARE
    product_row public.model_3d%ROWTYPE;
    model_asset public.media_asset%ROWTYPE;
    policy_cloud_name TEXT;
    expected_url TEXT;
    expected_public_id TEXT;
    locked_member_count INTEGER;
    image_count BIGINT;
    primary_count BIGINT;
BEGIN
    IF p_product_id IS NULL OR p_product_id <= 0 THEN
        RAISE EXCEPTION 'Invalid product identifier' USING ERRCODE = '22023';
    END IF;

    PERFORM public.admin3d_lock_product(p_product_id);
    SELECT model.* INTO product_row
    FROM public.model_3d AS model
    WHERE model.model_id = p_product_id;
    IF NOT FOUND OR product_row.source_type <> 'DESIGNER_MODEL' THEN
        RAISE EXCEPTION 'Designer model was not found' USING ERRCODE = 'P0002';
    END IF;
    -- Preserve existing ACTIVE idempotence: previously published products remain a no-op.
    IF product_row.model_status = 'ACTIVE' THEN
        RETURN p_product_id;
    END IF;
    IF product_row.model_status <> 'PENDING' THEN
        RAISE EXCEPTION 'Designer model is not eligible for publication' USING ERRCODE = 'P0001';
    END IF;

    SELECT policy.cloud_name INTO policy_cloud_name
    FROM public.admin3d_publication_policy AS policy
    WHERE policy.singleton = TRUE;
    IF NOT FOUND OR policy_cloud_name IS NULL OR policy_cloud_name = ''
            OR policy_cloud_name !~ '^[A-Za-z0-9_-]+$' THEN
        RAISE EXCEPTION 'Publication cloud identity policy is missing or invalid' USING ERRCODE = 'P0001';
    END IF;

    IF product_row.name IS NULL OR product_row.name !~ '[^[:space:]]'
            OR product_row.file_path_3d IS NULL OR product_row.file_path_3d = ''
            OR product_row.media_asset_id IS NULL THEN
        RAISE EXCEPTION 'Designer model is not eligible for publication' USING ERRCODE = 'P0001';
    END IF;

    SELECT asset.* INTO model_asset
    FROM public.media_asset AS asset
    WHERE asset.id = product_row.media_asset_id;
    IF NOT FOUND OR model_asset.operation_id IS NULL THEN
        RAISE EXCEPTION 'Designer model media is not eligible for publication' USING ERRCODE = 'P0001';
    END IF;

    -- Lock every current member in stable ID order before reading provider identity or state.
    locked_member_count := public.admin3d_lock_media_operation(model_asset.operation_id);
    SELECT model.* INTO product_row
    FROM public.model_3d AS model
    WHERE model.model_id = p_product_id;
    SELECT asset.* INTO model_asset
    FROM public.media_asset AS asset
    WHERE asset.id = product_row.media_asset_id;
    IF product_row.media_asset_id IS NULL OR NOT FOUND
            OR model_asset.operation_id IS NULL
            OR model_asset.media_kind <> 'MODEL_3D'
            OR model_asset.state <> 'ATTACHED'
            OR model_asset.provider <> 'CLOUDINARY'
            OR model_asset.cloud_name <> policy_cloud_name
            OR model_asset.provider_asset_id IS NULL OR model_asset.provider_asset_id !~ '[^[:space:]]'
            OR model_asset.byte_count <= 0
            OR model_asset.provider_version IS NULL OR model_asset.provider_version <= 0
            OR model_asset.resource_type <> 'raw'
            OR model_asset.delivery_type <> 'upload'
            OR pg_catalog.lower(pg_catalog.btrim(model_asset.format)) <> 'glb'
            OR product_row.file_path_3d <> model_asset.public_id THEN
        RAISE EXCEPTION 'Designer model media is not eligible for publication' USING ERRCODE = 'P0001';
    END IF;

    expected_public_id := 'product-media/models/' || model_asset.operation_id::TEXT || '/'
            || model_asset.id::TEXT || '.glb';
    expected_url := 'https://res.cloudinary.com/' || policy_cloud_name || '/raw/upload/v'
            || model_asset.provider_version::TEXT || '/' || expected_public_id;
    IF model_asset.public_id <> expected_public_id
            OR model_asset.secure_url IS DISTINCT FROM expected_url THEN
        RAISE EXCEPTION 'Designer model media identity is not eligible for publication' USING ERRCODE = 'P0001';
    END IF;

    -- Lock child image rows after the product and operation members; no publication check can race an edit.
    PERFORM image.image_id
    FROM public.model_images AS image
    WHERE image.model_id = p_product_id
    ORDER BY image.image_id
    FOR UPDATE;

    SELECT pg_catalog.count(*), pg_catalog.count(*) FILTER (WHERE image.is_primary IS TRUE)
    INTO image_count, primary_count
    FROM public.model_images AS image
    WHERE image.model_id = p_product_id;
    IF image_count < 1 OR primary_count <> 1 THEN
        RAISE EXCEPTION 'Designer model images are not eligible for publication' USING ERRCODE = 'P0001';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.model_images AS image
        LEFT JOIN public.media_asset AS asset ON asset.id = image.media_asset_id
        WHERE image.model_id = p_product_id
          AND (image.image_path IS NULL OR asset.id IS NULL
            OR image.image_path <> asset.public_id
            OR asset.operation_id <> model_asset.operation_id
            OR asset.media_kind <> 'IMAGE'
            OR asset.state <> 'ATTACHED'
            OR asset.provider <> 'CLOUDINARY'
            OR asset.cloud_name <> policy_cloud_name
            OR asset.provider_asset_id IS NULL OR asset.provider_asset_id !~ '[^[:space:]]'
            OR asset.byte_count <= 0
            OR asset.provider_version IS NULL OR asset.provider_version <= 0
            OR asset.resource_type <> 'image'
            OR asset.delivery_type <> 'upload'
            OR pg_catalog.lower(pg_catalog.btrim(asset.format)) NOT IN ('jpeg', 'jpg', 'png', 'webp')
            OR asset.public_id <> 'product-media/images/' || asset.operation_id::TEXT || '/' || asset.id::TEXT
            OR asset.secure_url IS DISTINCT FROM 'https://res.cloudinary.com/' || policy_cloud_name || '/image/upload/v'
                || asset.provider_version::TEXT || '/' || asset.public_id || '.' || asset.format)
    ) THEN
        RAISE EXCEPTION 'Designer model images are not eligible for publication' USING ERRCODE = 'P0001';
    END IF;

    UPDATE public.model_3d
    SET model_status = 'ACTIVE'
    WHERE model_id = p_product_id AND source_type = 'DESIGNER_MODEL' AND model_status = 'PENDING';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Designer model state changed during publication' USING ERRCODE = 'P0001';
    END IF;
    RETURN p_product_id;
END;
$$;

CREATE FUNCTION public.admin3d_edit_designer_model(
    p_product_id INTEGER,
    p_update_name BOOLEAN, p_name TEXT,
    p_update_description BOOLEAN, p_description TEXT,
    p_update_category BOOLEAN, p_category TEXT)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
SET lock_timeout = '1500ms'
AS $$
DECLARE
    product_row public.model_3d%ROWTYPE;
    normalized_name TEXT;
    normalized_description TEXT;
    normalized_category TEXT;
BEGIN
    IF p_product_id IS NULL OR p_product_id <= 0
            OR p_update_name IS NULL OR p_update_description IS NULL OR p_update_category IS NULL THEN
        RAISE EXCEPTION 'Invalid designer product metadata' USING ERRCODE = '22023';
    END IF;

    normalized_name := pg_catalog.regexp_replace(COALESCE(p_name, ''),
        E'^[\\001-\\040]+|[\\001-\\040]+$', '', 'g');
    normalized_description := pg_catalog.regexp_replace(COALESCE(p_description, ''),
        E'^[\\001-\\040]+|[\\001-\\040]+$', '', 'g');
    normalized_category := pg_catalog.regexp_replace(COALESCE(p_category, ''),
        E'^[\\001-\\040]+|[\\001-\\040]+$', '', 'g');
    IF (p_update_name AND (p_name IS NULL OR normalized_name !~ '[^[:space:]]'
            OR pg_catalog.char_length(normalized_name) > 150))
            OR (p_update_description AND pg_catalog.char_length(normalized_description) > 255)
            OR (p_update_category AND pg_catalog.char_length(normalized_category) > 100) THEN
        RAISE EXCEPTION 'Invalid designer product metadata' USING ERRCODE = '22023';
    END IF;

    PERFORM public.admin3d_lock_product(p_product_id);
    SELECT model.* INTO product_row
    FROM public.model_3d AS model
    WHERE model.model_id = p_product_id;
    IF NOT FOUND OR product_row.source_type <> 'DESIGNER_MODEL' THEN
        RAISE EXCEPTION 'Designer model was not found' USING ERRCODE = 'P0002';
    END IF;

    IF NOT p_update_name AND NOT p_update_description AND NOT p_update_category THEN
        RETURN p_product_id;
    END IF;

    UPDATE public.model_3d
    SET name = CASE WHEN p_update_name THEN normalized_name ELSE name END,
        description = CASE WHEN p_update_description
            THEN NULLIF(normalized_description, '') ELSE description END,
        category = CASE WHEN p_update_category
            THEN NULLIF(normalized_category, '') ELSE category END
    WHERE model_id = p_product_id AND source_type = 'DESIGNER_MODEL';
    RETURN p_product_id;
END;
$$;

CREATE FUNCTION public.admin3d_archive_designer_model(p_product_id INTEGER)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
SET lock_timeout = '1500ms'
AS $$
DECLARE
    product_row public.model_3d%ROWTYPE;
BEGIN
    IF p_product_id IS NULL OR p_product_id <= 0 THEN
        RAISE EXCEPTION 'Invalid product identifier' USING ERRCODE = '22023';
    END IF;
    PERFORM public.admin3d_lock_product(p_product_id);
    SELECT model.* INTO product_row
    FROM public.model_3d AS model
    WHERE model.model_id = p_product_id;
    IF NOT FOUND OR product_row.source_type <> 'DESIGNER_MODEL' THEN
        RAISE EXCEPTION 'Designer model was not found' USING ERRCODE = 'P0002';
    END IF;
    IF product_row.model_status <> 'INACTIVE' THEN
        UPDATE public.model_3d SET model_status = 'INACTIVE'
        WHERE model_id = p_product_id AND source_type = 'DESIGNER_MODEL';
    END IF;
    RETURN p_product_id;
END;
$$;

REVOKE ALL ON FUNCTION public.admin3d_publish_designer_model(INTEGER) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.admin3d_edit_designer_model(INTEGER, BOOLEAN, TEXT, BOOLEAN, TEXT, BOOLEAN, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.admin3d_archive_designer_model(INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.admin3d_publish_designer_model(INTEGER) TO app_runtime;
GRANT EXECUTE ON FUNCTION public.admin3d_edit_designer_model(INTEGER, BOOLEAN, TEXT, BOOLEAN, TEXT, BOOLEAN, TEXT) TO app_runtime;
GRANT EXECUTE ON FUNCTION public.admin3d_archive_designer_model(INTEGER) TO app_runtime;

RESET ROLE;
REVOKE CREATE ON SCHEMA public FROM admin3d_command_owner;
REVOKE CREATE ON SCHEMA public FROM app_runtime;
REVOKE UPDATE ON TABLE public.model_3d FROM app_runtime;
REVOKE UPDATE (model_id, source_type, user_id, name, slug, description, file_path_3d,
    size_x_mm, size_y_mm, size_z_mm, weight, category, model_status, created_at,
    session_id, variant_index, media_asset_id, validated_material_consumed_grams,
    validated_print_time_seconds, slicing_data_source, slicing_data_validated_at)
    ON TABLE public.model_3d FROM app_runtime;

DO $$
BEGIN
    IF pg_catalog.has_table_privilege('app_runtime', 'public.model_3d', 'UPDATE')
            OR pg_catalog.has_any_column_privilege('app_runtime', 'public.model_3d', 'UPDATE') THEN
        RAISE EXCEPTION 'app_runtime must not retain effective UPDATE access to model_3d' USING ERRCODE = '55000';
    END IF;
    IF pg_catalog.pg_has_role('app_runtime', 'admin3d_command_owner', 'MEMBER')
            OR pg_catalog.has_schema_privilege('app_runtime', 'public', 'CREATE')
            OR pg_catalog.has_table_privilege('app_runtime', 'public.admin3d_publication_policy', 'SELECT') THEN
        RAISE EXCEPTION 'app_runtime retains a forbidden V17 privilege' USING ERRCODE = '55000';
    END IF;
END;
$$;
