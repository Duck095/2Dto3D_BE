-- Additive private AI Studio pipeline. Legacy generation tables and meanings are untouched.
CREATE TABLE design_projects (
    project_id UUID PRIMARY KEY,
    owner_user_id INTEGER NOT NULL REFERENCES users(user_id) ON DELETE RESTRICT,
    product_type VARCHAR(24) NOT NULL,
    keychain_variant VARCHAR(16),
    style_id VARCHAR(64) NOT NULL,
    input_mode VARCHAR(16) NOT NULL,
    original_idea TEXT,
    normalized_prompt TEXT NOT NULL,
    prompt_profile_version VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    create_idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_design_projects_owner_key UNIQUE(owner_user_id, create_idempotency_key),
    CONSTRAINT uq_design_projects_owner_id UNIQUE(project_id, owner_user_id),
    CONSTRAINT ck_design_projects_product CHECK(product_type IN ('keychain','night-light','figure')),
    CONSTRAINT ck_design_projects_variant CHECK((product_type = 'keychain' AND keychain_variant IS NOT NULL AND keychain_variant IN ('ordinary','click')) OR (product_type <> 'keychain' AND keychain_variant IS NULL)),
    CONSTRAINT ck_design_projects_input_mode CHECK(input_mode IN ('text','image','multi')),
    CONSTRAINT ck_design_projects_status CHECK(status IN ('DRAFT','REFERENCES_QUEUED','REFERENCES_GENERATING','REFERENCES_REVIEW','REFERENCES_APPROVED','MODEL_QUEUED','MODEL_GENERATING','MODEL_REVIEW','COMPLETED','FAILED')),
    CONSTRAINT ck_design_projects_key CHECK(length(trim(create_idempotency_key)) BETWEEN 1 AND 128),
    CONSTRAINT ck_design_projects_fingerprint CHECK(request_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE TABLE design_project_inputs (
    input_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    owner_user_id INTEGER NOT NULL,
    input_order INTEGER NOT NULL CHECK(input_order BETWEEN 0 AND 7),
    artifact_key VARCHAR(512) NOT NULL,
    mime_type VARCHAR(64) NOT NULL CHECK(mime_type IN ('image/png','image/jpeg','image/webp')),
    byte_size BIGINT NOT NULL CHECK(byte_size > 0),
    sha256 CHAR(64) NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
    client_slot VARCHAR(64),
    view_hint VARCHAR(16),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_design_input_project_owner FOREIGN KEY(project_id, owner_user_id) REFERENCES design_projects(project_id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT uq_design_input_order UNIQUE(project_id, input_order),
    CONSTRAINT ck_design_input_view CHECK(view_hint IS NULL OR view_hint IN ('front','left','right','back'))
);

CREATE TABLE design_reference_sets (
    reference_set_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    owner_user_id INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_design_refset_project_owner FOREIGN KEY(project_id, owner_user_id) REFERENCES design_projects(project_id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT uq_design_refset_project UNIQUE(project_id),
    CONSTRAINT uq_design_refset_owner UNIQUE(reference_set_id, owner_user_id),
    CONSTRAINT uq_design_refset_project_owner UNIQUE(reference_set_id, project_id, owner_user_id)
);

CREATE TABLE design_reference_set_versions (
    reference_version_id UUID PRIMARY KEY,
    reference_set_id UUID NOT NULL,
    project_id UUID NOT NULL,
    owner_user_id INTEGER NOT NULL,
    version_number INTEGER NOT NULL CHECK(version_number > 0),
    parent_version_id UUID,
    status VARCHAR(24) NOT NULL CHECK(status IN ('GENERATING','READY_FOR_REVIEW','APPROVED','CHANGES_REQUESTED','FAILED')),
    normalized_prompt TEXT NOT NULL,
    prompt_profile_version VARCHAR(16) NOT NULL,
    revision_feedback TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_design_refver_set_project_owner FOREIGN KEY(reference_set_id, project_id, owner_user_id) REFERENCES design_reference_sets(reference_set_id, project_id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT uq_design_refver_number UNIQUE(reference_set_id, version_number),
    CONSTRAINT uq_design_refver_owner UNIQUE(reference_version_id, owner_user_id),
    CONSTRAINT uq_design_refver_project_owner UNIQUE(reference_version_id, project_id, owner_user_id),
    CONSTRAINT uq_design_refver_set_owner UNIQUE(reference_version_id, reference_set_id, project_id, owner_user_id),
    CONSTRAINT fk_design_refver_parent_same_set FOREIGN KEY(parent_version_id, reference_set_id, project_id, owner_user_id)
        REFERENCES design_reference_set_versions(reference_version_id, reference_set_id, project_id, owner_user_id) ON DELETE RESTRICT
);

CREATE TABLE design_reference_image_versions (
    image_version_id UUID PRIMARY KEY,
    reference_set_id UUID NOT NULL,
    project_id UUID NOT NULL,
    owner_user_id INTEGER NOT NULL,
    view_slot VARCHAR(16) NOT NULL CHECK(view_slot IN ('front','left','right','back')),
    version_number INTEGER NOT NULL CHECK(version_number > 0),
    supersedes_image_version_id UUID,
    artifact_key VARCHAR(512) NOT NULL,
    mime_type VARCHAR(64) NOT NULL CHECK(mime_type IN ('image/png','image/jpeg','image/webp')),
    byte_size BIGINT NOT NULL CHECK(byte_size > 0),
    sha256 CHAR(64) NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
    normalized_prompt TEXT NOT NULL,
    prompt_profile_version VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_design_image_set_project_owner FOREIGN KEY(reference_set_id, project_id, owner_user_id) REFERENCES design_reference_sets(reference_set_id, project_id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT uq_design_image_version UNIQUE(reference_set_id, view_slot, version_number),
    CONSTRAINT uq_design_image_owner UNIQUE(image_version_id, owner_user_id),
    CONSTRAINT uq_design_image_set_owner UNIQUE(image_version_id, reference_set_id, owner_user_id),
    CONSTRAINT uq_design_image_lineage UNIQUE(image_version_id, reference_set_id, view_slot, owner_user_id),
    CONSTRAINT fk_design_image_supersedes_same_slot FOREIGN KEY(supersedes_image_version_id, reference_set_id, view_slot, owner_user_id)
        REFERENCES design_reference_image_versions(image_version_id, reference_set_id, view_slot, owner_user_id) ON DELETE RESTRICT
);

CREATE TABLE design_reference_set_views (
    reference_version_id UUID NOT NULL,
    reference_set_id UUID NOT NULL,
    project_id UUID NOT NULL,
    view_slot VARCHAR(16) NOT NULL CHECK(view_slot IN ('front','left','right','back')),
    image_version_id UUID NOT NULL,
    owner_user_id INTEGER NOT NULL,
    PRIMARY KEY(reference_version_id, view_slot),
    CONSTRAINT fk_design_view_version_project_owner FOREIGN KEY(reference_version_id, reference_set_id, project_id, owner_user_id) REFERENCES design_reference_set_versions(reference_version_id, reference_set_id, project_id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_design_view_image_set_slot_owner FOREIGN KEY(image_version_id, reference_set_id, view_slot, owner_user_id) REFERENCES design_reference_image_versions(image_version_id, reference_set_id, view_slot, owner_user_id) ON DELETE RESTRICT
);

CREATE TABLE design_model_versions (
    model_version_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    owner_user_id INTEGER NOT NULL,
    reference_version_id UUID NOT NULL,
    version_number INTEGER NOT NULL CHECK(version_number > 0),
    supersedes_model_version_id UUID,
    status VARCHAR(24) NOT NULL CHECK(status IN ('GENERATING','READY_FOR_REVIEW','APPROVED','CHANGES_REQUESTED','FAILED')),
    artifact_key VARCHAR(512),
    mime_type VARCHAR(64),
    byte_size BIGINT,
    sha256 CHAR(64),
    provider_task_ref VARCHAR(256),
    revision_feedback TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_design_model_project_owner FOREIGN KEY(project_id, owner_user_id) REFERENCES design_projects(project_id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_design_model_reference_owner FOREIGN KEY(reference_version_id, owner_user_id) REFERENCES design_reference_set_versions(reference_version_id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT uq_design_model_number UNIQUE(project_id, version_number),
    CONSTRAINT uq_design_model_owner UNIQUE(model_version_id, owner_user_id),
    CONSTRAINT uq_design_model_project_owner UNIQUE(model_version_id, project_id, owner_user_id),
    CONSTRAINT fk_design_model_supersedes_same_project FOREIGN KEY(supersedes_model_version_id, project_id, owner_user_id)
        REFERENCES design_model_versions(model_version_id, project_id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_design_model_reference_project_owner FOREIGN KEY(reference_version_id, project_id, owner_user_id) REFERENCES design_reference_set_versions(reference_version_id, project_id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_design_model_artifact CHECK((artifact_key IS NULL AND mime_type IS NULL AND byte_size IS NULL AND sha256 IS NULL) OR (artifact_key IS NOT NULL AND mime_type IS NOT NULL AND mime_type = 'model/gltf-binary' AND byte_size IS NOT NULL AND byte_size > 0 AND sha256 IS NOT NULL AND sha256 ~ '^[0-9a-f]{64}$'))
);

CREATE TABLE design_reference_approvals (
    approval_id UUID PRIMARY KEY,
    reference_version_id UUID NOT NULL,
    owner_user_id INTEGER NOT NULL,
    decision VARCHAR(24) NOT NULL CHECK(decision IN ('APPROVED','CHANGES_REQUESTED')),
    feedback TEXT,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL CHECK(request_fingerprint ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_design_refapproval_owner FOREIGN KEY(reference_version_id, owner_user_id) REFERENCES design_reference_set_versions(reference_version_id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT uq_design_refapproval_idempotency UNIQUE(owner_user_id, idempotency_key)
);

CREATE TABLE design_model_approvals (
    approval_id UUID PRIMARY KEY,
    model_version_id UUID NOT NULL,
    owner_user_id INTEGER NOT NULL,
    decision VARCHAR(24) NOT NULL CHECK(decision IN ('APPROVED','CHANGES_REQUESTED')),
    feedback TEXT,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL CHECK(request_fingerprint ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_design_modelapproval_owner FOREIGN KEY(model_version_id, owner_user_id) REFERENCES design_model_versions(model_version_id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT uq_design_modelapproval_idempotency UNIQUE(owner_user_id, idempotency_key)
);

CREATE TABLE design_pipeline_jobs (
    job_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    owner_user_id INTEGER NOT NULL,
    stage VARCHAR(24) NOT NULL CHECK(stage IN ('REFERENCE_IMAGE','MODEL_MESH')),
    status VARCHAR(24) NOT NULL CHECK(status IN ('PENDING','PROCESSING','COMPLETED','FAILED','CANCELLED')),
    reference_version_id UUID,
    model_version_id UUID,
    requested_view_slot VARCHAR(16) CHECK(requested_view_slot IS NULL OR requested_view_slot IN ('front','left','right','back')),
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL CHECK(request_fingerprint ~ '^[0-9a-f]{64}$'),
    provider_task_ref VARCHAR(256),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count >= 0),
    lease_until TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ,
    safe_error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_design_job_project_owner FOREIGN KEY(project_id, owner_user_id) REFERENCES design_projects(project_id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_design_job_reference_owner FOREIGN KEY(reference_version_id, owner_user_id) REFERENCES design_reference_set_versions(reference_version_id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_design_job_model_owner FOREIGN KEY(model_version_id, owner_user_id) REFERENCES design_model_versions(model_version_id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_design_job_reference_project_owner FOREIGN KEY(reference_version_id, project_id, owner_user_id) REFERENCES design_reference_set_versions(reference_version_id, project_id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_design_job_model_project_owner FOREIGN KEY(model_version_id, project_id, owner_user_id) REFERENCES design_model_versions(model_version_id, project_id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT uq_design_job_idempotency UNIQUE(owner_user_id, stage, idempotency_key),
    CONSTRAINT uq_design_job_id_owner UNIQUE(job_id, owner_user_id),
    CONSTRAINT ck_design_job_target CHECK((stage = 'REFERENCE_IMAGE' AND reference_version_id IS NOT NULL AND model_version_id IS NULL) OR (stage = 'MODEL_MESH' AND model_version_id IS NOT NULL AND reference_version_id IS NULL))
);

CREATE TABLE design_usage_budget_windows (
    budget_window_id UUID PRIMARY KEY,
    scope_key VARCHAR(64) NOT NULL,
    window_date DATE NOT NULL,
    limit_units BIGINT NOT NULL CHECK(limit_units > 0),
    reserved_units BIGINT NOT NULL DEFAULT 0 CHECK(reserved_units >= 0),
    settled_units BIGINT NOT NULL DEFAULT 0 CHECK(settled_units >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_design_budget_scope_date UNIQUE(scope_key, window_date),
    CONSTRAINT ck_design_budget_scope CHECK(scope_key = 'GLOBAL' OR scope_key ~ '^USER:[1-9][0-9]*$'),
    CONSTRAINT ck_design_budget_limit CHECK(reserved_units + settled_units <= limit_units)
);

CREATE TABLE design_usage_reservations (
    reservation_id UUID PRIMARY KEY,
    job_id UUID NOT NULL UNIQUE,
    owner_user_id INTEGER NOT NULL REFERENCES users(user_id) ON DELETE RESTRICT,
    global_budget_window_id UUID NOT NULL REFERENCES design_usage_budget_windows(budget_window_id) ON DELETE RESTRICT,
    owner_budget_window_id UUID NOT NULL REFERENCES design_usage_budget_windows(budget_window_id) ON DELETE RESTRICT,
    reserved_units BIGINT NOT NULL CHECK(reserved_units > 0),
    settled_units BIGINT,
    status VARCHAR(16) NOT NULL CHECK(status IN ('RESERVED','SETTLED','RELEASED','UNKNOWN')),
    provider_usage_reference VARCHAR(256),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    settled_at TIMESTAMPTZ,
    CONSTRAINT fk_design_reservation_job_owner FOREIGN KEY(job_id, owner_user_id) REFERENCES design_pipeline_jobs(job_id, owner_user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_design_reservation_settlement CHECK((status = 'SETTLED' AND settled_units IS NOT NULL AND settled_units >= 0 AND settled_at IS NOT NULL) OR (status <> 'SETTLED' AND settled_units IS NULL AND settled_at IS NULL))
);

CREATE INDEX ix_design_projects_owner_history ON design_projects(owner_user_id, created_at DESC, project_id DESC);
CREATE INDEX ix_design_pipeline_jobs_claim ON design_pipeline_jobs(status, next_attempt_at, lease_until, created_at) WHERE status IN ('PENDING','PROCESSING');
CREATE INDEX ix_design_model_project_version ON design_model_versions(project_id, version_number DESC);
CREATE INDEX ix_design_refversion_history ON design_reference_set_versions(reference_set_id, version_number DESC);
