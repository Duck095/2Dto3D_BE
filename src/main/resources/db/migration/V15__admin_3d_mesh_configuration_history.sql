-- Additive Admin GLB mesh history schema.
--
-- Role provisioning is intentionally external to Flyway. Run this migration
-- only after a controlled migration identity has membership in the approved
-- non-login table owner. The runtime role must not own these objects.
-- This migration creates no roles, credentials, seed rows, or backfill.

SET ROLE admin3d_owner;

CREATE TABLE public.product_glb_structure_snapshots (
    structure_snapshot_id UUID PRIMARY KEY,
    product_id INTEGER NOT NULL,
    media_asset_id BIGINT NOT NULL,
    snapshot_revision BIGINT NOT NULL,
    parser_version VARCHAR(40) NOT NULL,
    structure_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by INTEGER NOT NULL,
    CONSTRAINT ck_glb_structure_snapshot_revision
        CHECK (snapshot_revision > 0),
    CONSTRAINT ck_glb_structure_parser_version
        CHECK (length(btrim(parser_version)) > 0),
    CONSTRAINT ck_glb_structure_json_object
        CHECK (jsonb_typeof(structure_json) = 'object'),
    CONSTRAINT fk_glb_structure_product
        FOREIGN KEY (product_id) REFERENCES public.model_3d (model_id) ON DELETE RESTRICT,
    CONSTRAINT fk_glb_structure_media
        FOREIGN KEY (media_asset_id) REFERENCES public.media_asset (id) ON DELETE RESTRICT,
    CONSTRAINT fk_glb_structure_creator
        FOREIGN KEY (created_by) REFERENCES public.users (user_id) ON DELETE RESTRICT,
    CONSTRAINT uq_glb_structure_revision
        UNIQUE (product_id, media_asset_id, snapshot_revision),
    CONSTRAINT uq_glb_structure_identity
        UNIQUE (product_id, media_asset_id, structure_snapshot_id)
);

CREATE TABLE public.product_mesh_configuration_revisions (
    configuration_revision_id UUID PRIMARY KEY,
    product_id INTEGER NOT NULL,
    media_asset_id BIGINT NOT NULL,
    structure_snapshot_id UUID NOT NULL,
    config_revision BIGINT NOT NULL,
    material_id INTEGER NOT NULL,
    configuration_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by INTEGER NOT NULL,
    CONSTRAINT ck_mesh_config_revision
        CHECK (config_revision > 0),
    CONSTRAINT ck_mesh_config_json_object
        CHECK (jsonb_typeof(configuration_json) = 'object'),
    CONSTRAINT fk_mesh_config_product
        FOREIGN KEY (product_id) REFERENCES public.model_3d (model_id) ON DELETE RESTRICT,
    CONSTRAINT fk_mesh_config_media
        FOREIGN KEY (media_asset_id) REFERENCES public.media_asset (id) ON DELETE RESTRICT,
    CONSTRAINT fk_mesh_config_material
        FOREIGN KEY (material_id) REFERENCES public.materials (material_id) ON DELETE RESTRICT,
    CONSTRAINT fk_mesh_config_creator
        FOREIGN KEY (created_by) REFERENCES public.users (user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_mesh_config_structure
        FOREIGN KEY (product_id, media_asset_id, structure_snapshot_id)
        REFERENCES public.product_glb_structure_snapshots
            (product_id, media_asset_id, structure_snapshot_id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_mesh_config_revision
        UNIQUE (product_id, media_asset_id, config_revision)
);

-- The unique revision indexes above support equality-scoped latest-row reads
-- (ORDER BY revision DESC). These leading-media indexes serve cleanup EXISTS
-- checks for either immutable history table.
CREATE INDEX ix_glb_structure_asset
    ON public.product_glb_structure_snapshots (media_asset_id, product_id);
CREATE INDEX ix_mesh_config_asset
    ON public.product_mesh_configuration_revisions (media_asset_id);

CREATE FUNCTION public.reject_admin_3d_history_mutation()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog
AS $$
BEGIN
    RAISE EXCEPTION 'Admin 3D history rows are immutable'
        USING ERRCODE = '55000';
    RETURN NULL;
END;
$$;

-- Trigger execution is the only intended use of this function. Runtime
-- function privilege requirements must be verified in the PostgreSQL 16
-- disposable role test before final grants are approved.
REVOKE ALL ON FUNCTION public.reject_admin_3d_history_mutation() FROM PUBLIC;

CREATE TRIGGER trg_glb_structure_no_update_delete
    BEFORE UPDATE OR DELETE ON public.product_glb_structure_snapshots
    FOR EACH ROW EXECUTE FUNCTION public.reject_admin_3d_history_mutation();
CREATE TRIGGER trg_glb_structure_no_truncate
    BEFORE TRUNCATE ON public.product_glb_structure_snapshots
    FOR EACH STATEMENT EXECUTE FUNCTION public.reject_admin_3d_history_mutation();

CREATE TRIGGER trg_mesh_config_no_update_delete
    BEFORE UPDATE OR DELETE ON public.product_mesh_configuration_revisions
    FOR EACH ROW EXECUTE FUNCTION public.reject_admin_3d_history_mutation();
CREATE TRIGGER trg_mesh_config_no_truncate
    BEFORE TRUNCATE ON public.product_mesh_configuration_revisions
    FOR EACH STATEMENT EXECUTE FUNCTION public.reject_admin_3d_history_mutation();

REVOKE ALL ON TABLE public.product_glb_structure_snapshots,
    public.product_mesh_configuration_revisions FROM PUBLIC;

RESET ROLE;
