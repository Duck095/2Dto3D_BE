-- Additive payOS merchant trust boundary. V1-V12 payment/link history is retained as-is.
-- A DBA must provision the internal NOLOGIN owner role and grant it to the dedicated Flyway
-- principal before migration. The application runtime principal must never receive this membership.

DO $trust_role$
DECLARE
    owner_role_oid OID;
    owner_can_login BOOLEAN;
    owner_is_superuser BOOLEAN;
    owner_can_create_databases BOOLEAN;
    owner_can_create_roles BOOLEAN;
    owner_can_replicate BOOLEAN;
    owner_inherits BOOLEAN;
    owner_can_bypass_rls BOOLEAN;
    migrator_can_create_roles BOOLEAN;
    migrator_is_superuser BOOLEAN;
BEGIN
    SELECT oid, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolinherit, rolbypassrls
      INTO owner_role_oid, owner_can_login, owner_is_superuser, owner_can_create_databases,
           owner_can_create_roles, owner_can_replicate, owner_inherits, owner_can_bypass_rls
      FROM pg_catalog.pg_roles WHERE rolname = 'payos_trust_owner';
    IF owner_role_oid IS NULL THEN
        SELECT rolcreaterole, rolsuper INTO migrator_can_create_roles, migrator_is_superuser
          FROM pg_catalog.pg_roles WHERE rolname = current_user;
        IF NOT migrator_can_create_roles AND NOT migrator_is_superuser THEN
            RAISE EXCEPTION 'DBA must provision the internal payOS trust owner role before V13';
        END IF;
        CREATE ROLE payos_trust_owner NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
        EXECUTE format('GRANT payos_trust_owner TO %I', current_user);
        SELECT oid, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolinherit, rolbypassrls
          INTO owner_role_oid, owner_can_login, owner_is_superuser, owner_can_create_databases,
               owner_can_create_roles, owner_can_replicate, owner_inherits, owner_can_bypass_rls
          FROM pg_catalog.pg_roles WHERE rolname = 'payos_trust_owner';
    END IF;
    IF owner_can_login OR owner_is_superuser OR owner_can_create_databases OR owner_can_create_roles
        OR owner_can_replicate OR owner_inherits OR owner_can_bypass_rls THEN
        RAISE EXCEPTION 'existing internal payOS trust owner role has unsafe attributes';
    END IF;
    IF NOT pg_catalog.pg_has_role(current_user, 'payos_trust_owner', 'MEMBER') THEN
        SELECT rolcreaterole, rolsuper INTO migrator_can_create_roles, migrator_is_superuser
          FROM pg_catalog.pg_roles WHERE rolname = current_user;
        IF NOT migrator_can_create_roles AND NOT migrator_is_superuser THEN
            RAISE EXCEPTION 'Flyway principal must be granted membership in the internal payOS trust owner role';
        END IF;
        EXECUTE format('GRANT payos_trust_owner TO %I', current_user);
    END IF;
END;
$trust_role$;

CREATE TABLE payos_merchant_bindings (
    binding_id UUID PRIMARY KEY,
    merchant_channel_id VARCHAR(128) NOT NULL,
    binding_version INTEGER NOT NULL,
    environment VARCHAR(16) NOT NULL DEFAULT 'PRODUCTION',
    binding_state VARCHAR(24) NOT NULL DEFAULT 'PENDING_VERIFICATION',
    client_id VARCHAR(256) NOT NULL,
    api_key_secret_ref VARCHAR(512) NOT NULL,
    checksum_key_secret_ref VARCHAR(512) NOT NULL,
    secret_version_ref VARCHAR(512) NOT NULL,
    receiving_bank_bin VARCHAR(16) NOT NULL,
    receiving_account_fingerprint VARCHAR(64) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    approved_by VARCHAR(128),
    approved_at TIMESTAMP WITH TIME ZONE,
    verification_evidence_ref VARCHAR(512),
    verified_at TIMESTAMP WITH TIME ZONE,
    revoked_by VARCHAR(128),
    revoked_at TIMESTAMP WITH TIME ZONE,
    revocation_evidence_ref VARCHAR(512),
    CONSTRAINT uq_payos_binding_channel_version UNIQUE (merchant_channel_id, binding_version),
    CONSTRAINT uq_payos_binding_id_version UNIQUE (binding_id, binding_version),
    CONSTRAINT uq_payos_binding_identity_version_channel UNIQUE (binding_id, binding_version, merchant_channel_id),
    CONSTRAINT ck_payos_binding_channel CHECK (length(trim(merchant_channel_id)) BETWEEN 1 AND 128),
    CONSTRAINT ck_payos_binding_version CHECK (binding_version > 0),
    CONSTRAINT ck_payos_binding_environment CHECK (environment = 'PRODUCTION'),
    CONSTRAINT ck_payos_binding_state CHECK (binding_state IN ('PENDING_VERIFICATION', 'VERIFIED', 'REVOKED')),
    CONSTRAINT ck_payos_binding_client_id CHECK (length(trim(client_id)) BETWEEN 1 AND 256),
    CONSTRAINT ck_payos_binding_secret_refs CHECK (
        length(api_key_secret_ref) <= 512 AND api_key_secret_ref ~ '^[A-Za-z][A-Za-z0-9+.-]*://[^[:space:]]+$'
        AND length(checksum_key_secret_ref) <= 512 AND checksum_key_secret_ref ~ '^[A-Za-z][A-Za-z0-9+.-]*://[^[:space:]]+$'
        AND length(secret_version_ref) <= 512 AND secret_version_ref ~ '^[A-Za-z][A-Za-z0-9+.-]*://[^[:space:]]+$'),
    CONSTRAINT ck_payos_binding_receiver CHECK (
        receiving_bank_bin ~ '^[0-9]{6,8}$'
        AND receiving_account_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_payos_binding_created_by CHECK (length(trim(created_by)) > 0),
    CONSTRAINT ck_payos_binding_verified_fields CHECK (
        (binding_state = 'PENDING_VERIFICATION'
            AND approved_by IS NULL AND approved_at IS NULL AND verification_evidence_ref IS NULL AND verified_at IS NULL
            AND revoked_by IS NULL AND revoked_at IS NULL AND revocation_evidence_ref IS NULL)
        OR (binding_state = 'VERIFIED'
            AND length(trim(approved_by)) > 0 AND approved_at IS NOT NULL
            AND length(trim(verification_evidence_ref)) > 0 AND verified_at IS NOT NULL
            AND revoked_by IS NULL AND revoked_at IS NULL AND revocation_evidence_ref IS NULL)
        OR (binding_state = 'REVOKED'
            AND revoked_by IS NOT NULL AND revoked_at IS NOT NULL AND length(trim(revocation_evidence_ref)) > 0
            AND ((approved_by IS NULL AND approved_at IS NULL AND verification_evidence_ref IS NULL AND verified_at IS NULL)
                OR (length(trim(approved_by)) > 0 AND approved_at IS NOT NULL
                    AND length(trim(verification_evidence_ref)) > 0 AND verified_at IS NOT NULL)))
    )
);

CREATE TABLE payos_merchant_binding_audit (
    audit_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    binding_id UUID NOT NULL,
    binding_version INTEGER NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    actor VARCHAR(128) NOT NULL,
    evidence_ref VARCHAR(512) NOT NULL,
    attempt_id UUID,
    order_code BIGINT,
    amount_vnd BIGINT,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_payos_binding_audit_binding FOREIGN KEY (binding_id, binding_version)
        REFERENCES payos_merchant_bindings (binding_id, binding_version) ON DELETE RESTRICT,
    CONSTRAINT ck_payos_binding_audit_event CHECK (event_type IN (
        'BINDING_REGISTERED', 'BINDING_VERIFIED', 'BINDING_REVOKED', 'LIVE_LINK_AUTHORIZED')),
    CONSTRAINT ck_payos_binding_audit_actor CHECK (length(trim(actor)) > 0),
    CONSTRAINT ck_payos_binding_audit_evidence CHECK (length(trim(evidence_ref)) > 0),
    CONSTRAINT ck_payos_binding_audit_link CHECK (
        (event_type = 'LIVE_LINK_AUTHORIZED' AND attempt_id IS NOT NULL AND order_code > 0 AND amount_vnd > 0)
        OR (event_type <> 'LIVE_LINK_AUTHORIZED' AND attempt_id IS NULL AND order_code IS NULL AND amount_vnd IS NULL))
);

ALTER TABLE payos_payment_links
    ADD COLUMN merchant_binding_id UUID,
    ADD COLUMN merchant_binding_version INTEGER,
    ADD CONSTRAINT fk_payos_link_merchant_binding FOREIGN KEY
        (merchant_binding_id, merchant_binding_version, merchant_channel_id)
        REFERENCES payos_merchant_bindings (binding_id, binding_version, merchant_channel_id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_payos_link_binding_provenance CHECK (
        (provenance = 'TEST_ONLY' AND merchant_binding_id IS NULL AND merchant_binding_version IS NULL)
        OR (provenance = 'LIVE' AND merchant_binding_id IS NOT NULL AND merchant_binding_version IS NOT NULL)
    );

CREATE INDEX ix_payos_binding_channel_state
    ON payos_merchant_bindings (merchant_channel_id, binding_state, binding_version DESC);
CREATE INDEX ix_payos_binding_audit_history
    ON payos_merchant_binding_audit (binding_id, occurred_at DESC, audit_id DESC);
CREATE INDEX ix_payos_binding_audit_attempt
    ON payos_merchant_binding_audit (attempt_id, occurred_at DESC)
    WHERE attempt_id IS NOT NULL;

-- The trigger function is SECURITY INVOKER: the application role remains visible to the
-- guard, while approved SECURITY DEFINER routines execute as the migration-owned function owner.
CREATE FUNCTION payos_merchant_trust_guard() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    trusted_owner_oid OID := TG_ARGV[0]::OID;
    caller_oid OID;
BEGIN
    SELECT oid INTO caller_oid FROM pg_catalog.pg_roles WHERE rolname = current_user;

    IF TG_TABLE_NAME = 'payos_merchant_binding_audit' THEN
        IF TG_OP <> 'INSERT' THEN
            RAISE EXCEPTION 'payOS merchant audit is append-only' USING ERRCODE = '42501';
        END IF;
        IF caller_oid <> trusted_owner_oid THEN
            RAISE EXCEPTION 'payOS merchant audit writes require trusted binding function' USING ERRCODE = '42501';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_TABLE_NAME = 'payos_merchant_bindings' AND caller_oid <> trusted_owner_oid THEN
        RAISE EXCEPTION 'payOS merchant binding writes require trusted binding function' USING ERRCODE = '42501';
    END IF;

    IF TG_TABLE_NAME = 'payos_payment_links' THEN
        IF TG_OP = 'UPDATE' AND (
            NEW.provenance IS DISTINCT FROM OLD.provenance
            OR NEW.merchant_binding_id IS DISTINCT FROM OLD.merchant_binding_id
            OR NEW.merchant_binding_version IS DISTINCT FROM OLD.merchant_binding_version
        ) THEN
            RAISE EXCEPTION 'payOS link provenance and merchant binding are immutable' USING ERRCODE = '42501';
        END IF;
        IF TG_OP = 'INSERT' AND NEW.provenance = 'LIVE' AND caller_oid <> trusted_owner_oid THEN
            RAISE EXCEPTION 'LIVE payOS links require approved merchant function' USING ERRCODE = '42501';
        END IF;
        IF TG_OP = 'INSERT' AND NEW.provenance = 'TEST_ONLY'
            AND (NEW.merchant_binding_id IS NOT NULL OR NEW.merchant_binding_version IS NOT NULL) THEN
            RAISE EXCEPTION 'TEST_ONLY link cannot carry a LIVE merchant binding' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

DO $$
DECLARE
    trusted_owner_oid TEXT;
BEGIN
    SELECT oid::TEXT INTO trusted_owner_oid FROM pg_catalog.pg_roles WHERE rolname = 'payos_trust_owner';
    EXECUTE format('CREATE TRIGGER trg_payos_binding_trust_guard BEFORE INSERT OR UPDATE OR DELETE ON payos_merchant_bindings FOR EACH ROW EXECUTE FUNCTION payos_merchant_trust_guard(%L)', trusted_owner_oid);
    EXECUTE format('CREATE TRIGGER trg_payos_binding_audit_guard BEFORE INSERT OR UPDATE OR DELETE ON payos_merchant_binding_audit FOR EACH ROW EXECUTE FUNCTION payos_merchant_trust_guard(%L)', trusted_owner_oid);
    EXECUTE format('CREATE TRIGGER trg_payos_link_provenance_guard BEFORE INSERT OR UPDATE ON payos_payment_links FOR EACH ROW EXECUTE FUNCTION payos_merchant_trust_guard(%L)', trusted_owner_oid);
END;
$$;

DO $create_functions$
DECLARE
    trust_schema TEXT := current_schema();
BEGIN
    EXECUTE format($ddl$
        CREATE FUNCTION %I.payos_register_merchant_binding(
            p_binding_id UUID, p_channel_id TEXT, p_version INTEGER, p_client_id TEXT,
            p_api_key_secret_ref TEXT, p_checksum_secret_ref TEXT, p_secret_version_ref TEXT,
            p_receiving_bank_bin TEXT, p_receiving_account_fingerprint TEXT,
            p_evidence_ref TEXT)
        RETURNS UUID LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, %1$I, pg_temp
        AS $body$
        BEGIN
            IF p_binding_id IS NULL OR p_channel_id IS NULL OR btrim(p_channel_id) = ''
                OR p_version IS NULL OR p_version <= 0 OR p_client_id IS NULL OR btrim(p_client_id) = ''
                OR p_api_key_secret_ref IS NULL OR btrim(p_api_key_secret_ref) = ''
                OR p_checksum_secret_ref IS NULL OR btrim(p_checksum_secret_ref) = ''
                OR p_secret_version_ref IS NULL OR btrim(p_secret_version_ref) = ''
                OR length(p_api_key_secret_ref) > 512 OR p_api_key_secret_ref !~ '^[A-Za-z][A-Za-z0-9+.-]*://[^[:space:]]+$'
                OR length(p_checksum_secret_ref) > 512 OR p_checksum_secret_ref !~ '^[A-Za-z][A-Za-z0-9+.-]*://[^[:space:]]+$'
                OR length(p_secret_version_ref) > 512 OR p_secret_version_ref !~ '^[A-Za-z][A-Za-z0-9+.-]*://[^[:space:]]+$'
                OR p_receiving_bank_bin !~ '^[0-9]{6,8}$'
                OR p_receiving_account_fingerprint !~ '^[0-9a-f]{64}$'
                OR p_evidence_ref IS NULL OR btrim(p_evidence_ref) = '' THEN
                RAISE EXCEPTION 'merchant binding registration evidence is incomplete' USING ERRCODE = '23514';
            END IF;
            PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(p_channel_id, 0));
            INSERT INTO %1$I.payos_merchant_bindings (
                binding_id, merchant_channel_id, binding_version, environment, binding_state, client_id,
                api_key_secret_ref, checksum_key_secret_ref, secret_version_ref, receiving_bank_bin,
                receiving_account_fingerprint, created_by)
            VALUES (p_binding_id, p_channel_id, p_version, 'PRODUCTION', 'PENDING_VERIFICATION', p_client_id,
                p_api_key_secret_ref, p_checksum_secret_ref, p_secret_version_ref, p_receiving_bank_bin,
                p_receiving_account_fingerprint, session_user);
            INSERT INTO %1$I.payos_merchant_binding_audit(binding_id, binding_version, event_type, actor, evidence_ref)
            VALUES (p_binding_id, p_version, 'BINDING_REGISTERED', session_user, p_evidence_ref);
            RETURN p_binding_id;
        END
        $body$;
    $ddl$, trust_schema);

    EXECUTE format($ddl$
        CREATE FUNCTION %I.payos_transition_merchant_binding(
            p_binding_id UUID, p_target_state TEXT, p_evidence_ref TEXT)
        RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, %1$I, pg_temp
        AS $body$
        DECLARE
            binding_row %1$I.payos_merchant_bindings%%ROWTYPE;
            binding_channel_id TEXT;
        BEGIN
            IF p_evidence_ref IS NULL OR btrim(p_evidence_ref) = '' THEN
                RAISE EXCEPTION 'binding transition requires evidence' USING ERRCODE = '23514';
            END IF;
            SELECT merchant_channel_id INTO binding_channel_id FROM %1$I.payos_merchant_bindings WHERE binding_id = p_binding_id;
            IF NOT FOUND THEN RAISE EXCEPTION 'merchant binding not found' USING ERRCODE = 'P0002'; END IF;
            PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(binding_channel_id, 0));
            SELECT * INTO binding_row FROM %1$I.payos_merchant_bindings WHERE binding_id = p_binding_id FOR UPDATE;

            IF p_target_state = 'VERIFIED' AND binding_row.binding_state = 'PENDING_VERIFICATION' THEN
                UPDATE %1$I.payos_merchant_bindings SET binding_state = 'VERIFIED', approved_by = session_user,
                    approved_at = clock_timestamp(), verification_evidence_ref = p_evidence_ref,
                    verified_at = clock_timestamp()
                WHERE binding_id = p_binding_id;
                INSERT INTO %1$I.payos_merchant_binding_audit(binding_id, binding_version, event_type, actor, evidence_ref)
                VALUES (binding_row.binding_id, binding_row.binding_version, 'BINDING_VERIFIED', session_user, p_evidence_ref);
                RETURN;
            ELSIF p_target_state = 'REVOKED' AND binding_row.binding_state IN ('PENDING_VERIFICATION', 'VERIFIED') THEN
                UPDATE %1$I.payos_merchant_bindings SET binding_state = 'REVOKED', revoked_by = session_user,
                    revoked_at = clock_timestamp(), revocation_evidence_ref = p_evidence_ref
                WHERE binding_id = p_binding_id;
                INSERT INTO %1$I.payos_merchant_binding_audit(binding_id, binding_version, event_type, actor, evidence_ref)
                VALUES (binding_row.binding_id, binding_row.binding_version, 'BINDING_REVOKED', session_user, p_evidence_ref);
                RETURN;
            END IF;
            RAISE EXCEPTION 'invalid merchant binding state transition' USING ERRCODE = '23514';
        END
        $body$;
    $ddl$, trust_schema);

    EXECUTE format($ddl$
        CREATE FUNCTION %I.payos_create_live_payment_link(
            p_attempt_id UUID, p_channel_id TEXT, p_amount_vnd BIGINT,
            p_request_fingerprint TEXT, p_description TEXT, p_expires_at TIMESTAMP WITH TIME ZONE,
            p_binding_id UUID, p_binding_version INTEGER, p_receiving_account_fingerprint TEXT)
        RETURNS UUID LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, %1$I, pg_temp
        AS $body$
        DECLARE
            binding_row %1$I.payos_merchant_bindings%%ROWTYPE;
            attempt_row %1$I.payment_attempts%%ROWTYPE;
        BEGIN
            PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(p_channel_id, 0));
            SELECT * INTO binding_row FROM %1$I.payos_merchant_bindings
                WHERE binding_id = p_binding_id AND binding_version = p_binding_version FOR SHARE;
            IF NOT FOUND OR binding_row.binding_state <> 'VERIFIED' OR binding_row.verified_at IS NULL
                OR binding_row.approved_by IS NULL OR binding_row.verification_evidence_ref IS NULL
                OR binding_row.revoked_at IS NOT NULL OR binding_row.merchant_channel_id <> p_channel_id
                OR binding_row.receiving_account_fingerprint <> p_receiving_account_fingerprint
                OR binding_row.api_key_secret_ref IS NULL OR binding_row.checksum_key_secret_ref IS NULL
                OR binding_row.secret_version_ref IS NULL THEN
                RAISE EXCEPTION 'merchant binding is not verified for this channel and receiver' USING ERRCODE = '42501';
            END IF;
            IF binding_row.binding_version <> (SELECT max(binding_version) FROM %1$I.payos_merchant_bindings WHERE merchant_channel_id = p_channel_id) THEN
                RAISE EXCEPTION 'merchant binding version is no longer current' USING ERRCODE = '42501';
            END IF;
            SELECT * INTO attempt_row FROM %1$I.payment_attempts WHERE attempt_id = p_attempt_id FOR UPDATE;
            IF NOT FOUND OR attempt_row.provider <> 'PAYOS' OR attempt_row.status <> 'INITIATED'
                OR attempt_row.amount_vnd IS NULL OR attempt_row.amount_vnd <> p_amount_vnd
                OR p_amount_vnd <= 0 OR p_request_fingerprint !~ '^[0-9a-f]{64}$'
                OR p_description IS NULL OR length(btrim(p_description)) NOT BETWEEN 1 AND 9
                OR p_expires_at IS NULL OR p_expires_at <= clock_timestamp()
                OR EXISTS (SELECT 1 FROM %1$I.payos_payment_links WHERE attempt_id = p_attempt_id) THEN
                RAISE EXCEPTION 'payment attempt is not eligible for LIVE link creation' USING ERRCODE = '23514';
            END IF;
            INSERT INTO %1$I.payos_payment_links(
                attempt_id, provider, provenance, order_code, merchant_channel_id, amount_vnd,
                request_fingerprint, description, link_state, currency, expires_at,
                merchant_binding_id, merchant_binding_version)
            VALUES (p_attempt_id, 'PAYOS', 'LIVE', pg_catalog.nextval('%1$I.payos_order_code_seq'::pg_catalog.regclass), p_channel_id,
                p_amount_vnd, p_request_fingerprint, p_description, 'PREPARED', 'VND', p_expires_at,
                p_binding_id, p_binding_version);
            INSERT INTO %1$I.payos_merchant_binding_audit(
                binding_id, binding_version, event_type, actor, evidence_ref, attempt_id, order_code, amount_vnd)
            SELECT p_binding_id, p_binding_version, 'LIVE_LINK_AUTHORIZED', session_user,
                binding_row.verification_evidence_ref, p_attempt_id, order_code, amount_vnd
            FROM %1$I.payos_payment_links WHERE attempt_id = p_attempt_id;
            RETURN p_attempt_id;
        END
        $body$;
    $ddl$, trust_schema);
END;
$create_functions$;

REVOKE ALL ON payos_merchant_bindings, payos_merchant_binding_audit FROM PUBLIC;
REVOKE ALL ON SEQUENCE payos_merchant_binding_audit_audit_id_seq FROM PUBLIC;
REVOKE ALL ON FUNCTION payos_register_merchant_binding(UUID, TEXT, INTEGER, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION payos_transition_merchant_binding(UUID, TEXT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION payos_create_live_payment_link(UUID, TEXT, BIGINT, TEXT, TEXT, TIMESTAMP WITH TIME ZONE, UUID, INTEGER, TEXT) FROM PUBLIC;
DO $transfer_ownership$
DECLARE
    trust_schema TEXT := current_schema();
BEGIN
    EXECUTE format('GRANT USAGE, CREATE ON SCHEMA %I TO payos_trust_owner', trust_schema);
    EXECUTE format('GRANT SELECT ON payment_attempts TO payos_trust_owner');
    -- PostgreSQL requires UPDATE privilege for SELECT ... FOR UPDATE. A narrow, otherwise unused
    -- column grant enables the lock without giving the definer owner broad attempt mutation.
    EXECUTE format('GRANT UPDATE (error_code) ON payment_attempts TO payos_trust_owner');
    EXECUTE format('GRANT SELECT, INSERT ON payos_payment_links TO payos_trust_owner');
    EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE payos_order_code_seq TO payos_trust_owner');

    EXECUTE 'ALTER TABLE payos_merchant_bindings OWNER TO payos_trust_owner';
    EXECUTE 'ALTER TABLE payos_merchant_binding_audit OWNER TO payos_trust_owner';
    EXECUTE 'ALTER FUNCTION payos_merchant_trust_guard() OWNER TO payos_trust_owner';
    EXECUTE 'ALTER FUNCTION payos_register_merchant_binding(UUID, TEXT, INTEGER, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT) OWNER TO payos_trust_owner';
    EXECUTE 'ALTER FUNCTION payos_transition_merchant_binding(UUID, TEXT, TEXT) OWNER TO payos_trust_owner';
    EXECUTE 'ALTER FUNCTION payos_create_live_payment_link(UUID, TEXT, BIGINT, TEXT, TEXT, TIMESTAMP WITH TIME ZONE, UUID, INTEGER, TEXT) OWNER TO payos_trust_owner';
    EXECUTE format('REVOKE CREATE ON SCHEMA %I FROM payos_trust_owner', trust_schema);
END;
$transfer_ownership$;

