-- payOS storage expansion. V1-V11 and historical payment/provider rows remain unchanged.
-- No legacy row is relabeled or backfilled into PAYOS.

CREATE SEQUENCE payos_order_code_seq AS BIGINT START WITH 1 INCREMENT BY 1 NO CYCLE;

ALTER TABLE payments
    DROP CONSTRAINT ck_payments_method,
    ADD CONSTRAINT ck_payments_method CHECK (method IN ('VNPAY', 'MOMO', 'BANK_TRANSFER'));

ALTER TABLE payment_attempts
    DROP CONSTRAINT ck_payment_attempt_provider,
    ADD CONSTRAINT ck_payment_attempt_provider CHECK (provider IN ('VNPAY', 'MOMO', 'PAYOS', 'UNKNOWN'));

ALTER TABLE payment_callback_events
    DROP CONSTRAINT ck_payment_callback_provider,
    ADD CONSTRAINT ck_payment_callback_provider CHECK (provider IN ('VNPAY', 'MOMO', 'PAYOS'));

ALTER TABLE payment_reconciliation_cases
    DROP CONSTRAINT ck_payment_reconciliation_provider,
    ADD CONSTRAINT ck_payment_reconciliation_provider CHECK (provider IN ('VNPAY', 'MOMO', 'PAYOS'));

ALTER TABLE payment_reconciliation_cases
    ADD COLUMN merchant_channel_id VARCHAR(128),
    ADD COLUMN observed_order_code BIGINT,
    ADD COLUMN observed_payment_link_id VARCHAR(128),
    ADD COLUMN recipient_account_ciphertext TEXT,
    ADD COLUMN encryption_key_version SMALLINT;

CREATE TABLE payos_payment_links (
    attempt_id UUID PRIMARY KEY,
    provider VARCHAR(16) NOT NULL DEFAULT 'PAYOS',
    provenance VARCHAR(16) NOT NULL DEFAULT 'TEST_ONLY',
    order_code BIGINT NOT NULL DEFAULT nextval('payos_order_code_seq'),
    merchant_channel_id VARCHAR(128) NOT NULL,
    amount_vnd BIGINT NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    description VARCHAR(9) NOT NULL,
    link_state VARCHAR(24) NOT NULL DEFAULT 'PREPARED',
    payment_link_id VARCHAR(128),
    checkout_url_ciphertext TEXT,
    qr_payload_ciphertext TEXT,
    receiving_bank_bin VARCHAR(16),
    receiving_account_number_ciphertext TEXT,
    receiving_account_name VARCHAR(128),
    encryption_key_version SMALLINT,
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    provider_response_digest VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    link_created_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_payos_link_attempt_provider FOREIGN KEY (attempt_id, provider)
        REFERENCES payment_attempts (attempt_id, provider) ON DELETE RESTRICT,
    CONSTRAINT uq_payos_link_order_code UNIQUE (order_code),
    CONSTRAINT uq_payos_link_payment_link_id UNIQUE (payment_link_id),
    CONSTRAINT uq_payos_link_event_identity UNIQUE (attempt_id, merchant_channel_id, order_code, payment_link_id, amount_vnd),
    CONSTRAINT ck_payos_link_provider CHECK (provider = 'PAYOS'),
    CONSTRAINT ck_payos_link_provenance CHECK (provenance IN ('TEST_ONLY', 'LIVE')),
    CONSTRAINT ck_payos_link_order_code CHECK (order_code > 0),
    CONSTRAINT ck_payos_link_amount CHECK (amount_vnd > 0),
    CONSTRAINT ck_payos_link_fingerprint CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_payos_link_description CHECK (length(trim(description)) BETWEEN 1 AND 9),
    CONSTRAINT ck_payos_link_state CHECK (link_state IN (
        'PREPARED', 'SUBMITTING', 'LINK_CREATED', 'UNKNOWN', 'REJECTED', 'EXPIRED')),
    CONSTRAINT ck_payos_link_currency CHECK (currency = 'VND'),
    CONSTRAINT ck_payos_link_key_version CHECK (encryption_key_version IS NULL OR encryption_key_version > 0),
    CONSTRAINT ck_payos_link_response_digest CHECK (
        provider_response_digest IS NULL OR provider_response_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_payos_link_created_data CHECK (
        (link_state <> 'LINK_CREATED' OR payment_link_id IS NOT NULL)
        AND ((payment_link_id IS NOT NULL
            AND checkout_url_ciphertext IS NOT NULL AND qr_payload_ciphertext IS NOT NULL
            AND receiving_bank_bin IS NOT NULL AND receiving_account_number_ciphertext IS NOT NULL
            AND receiving_account_name IS NOT NULL AND encryption_key_version IS NOT NULL
            AND link_created_at IS NOT NULL)
        OR (payment_link_id IS NULL
            AND checkout_url_ciphertext IS NULL AND qr_payload_ciphertext IS NULL
            AND receiving_bank_bin IS NULL AND receiving_account_number_ciphertext IS NULL
            AND receiving_account_name IS NULL AND encryption_key_version IS NULL
            AND link_created_at IS NULL)))
);

CREATE INDEX ix_payos_payment_links_channel_expiry
    ON payos_payment_links (merchant_channel_id, expires_at);

CREATE TABLE payos_webhook_receipts (
    receipt_id UUID PRIMARY KEY,
    merchant_channel_id VARCHAR(128) NOT NULL,
    bank_reference VARCHAR(255) NOT NULL,
    event_digest VARCHAR(64) NOT NULL,
    attempt_id UUID,
    order_code BIGINT NOT NULL,
    payment_link_id VARCHAR(128),
    amount_vnd BIGINT NOT NULL,
    recipient_account_ciphertext TEXT NOT NULL,
    encryption_key_version SMALLINT NOT NULL,
    provider_status VARCHAR(32) NOT NULL,
    disposition VARCHAR(32) NOT NULL,
    signature_verified BOOLEAN NOT NULL DEFAULT FALSE,
    currency VARCHAR(8) NOT NULL DEFAULT 'UNKNOWN',
    signature_verified_at TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_payos_receipt_attempt FOREIGN KEY (attempt_id)
        REFERENCES payment_attempts (attempt_id) ON DELETE RESTRICT,
    CONSTRAINT fk_payos_receipt_link_identity FOREIGN KEY
        (attempt_id, merchant_channel_id, order_code, payment_link_id, amount_vnd)
        REFERENCES payos_payment_links (attempt_id, merchant_channel_id, order_code, payment_link_id, amount_vnd)
        ON DELETE RESTRICT,
    CONSTRAINT uq_payos_webhook_event_digest UNIQUE (merchant_channel_id, bank_reference, event_digest),
    CONSTRAINT ck_payos_receipt_digest CHECK (event_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_payos_receipt_key_version CHECK (encryption_key_version > 0),
    CONSTRAINT ck_payos_receipt_amount CHECK (amount_vnd >= 0),
    CONSTRAINT ck_payos_receipt_order_code CHECK (order_code >= 0),
    CONSTRAINT ck_payos_receipt_currency CHECK (currency ~ '^[A-Z]{3,8}$'),
    CONSTRAINT ck_payos_receipt_disposition CHECK (disposition IN (
        'RECEIVED', 'MATCHED', 'RECONCILIATION_REQUIRED', 'CONFLICT')),
    CONSTRAINT ck_payos_receipt_processed_at CHECK (
        (disposition = 'RECEIVED' AND processed_at IS NULL)
        OR (disposition <> 'RECEIVED' AND processed_at IS NOT NULL)),
    CONSTRAINT ck_payos_receipt_matched_trust CHECK (
        disposition <> 'MATCHED' OR (attempt_id IS NOT NULL AND payment_link_id IS NOT NULL
        AND provider_status = 'PAID' AND signature_verified AND currency = 'VND' AND amount_vnd > 0))
);

-- One verified bank reference can settle at most one order within a merchant channel.
-- Conflicting/unmatched evidence remains durable but cannot be applied as a settlement.
CREATE UNIQUE INDEX uq_payos_matched_bank_reference
    ON payos_webhook_receipts (merchant_channel_id, bank_reference)
    WHERE disposition = 'MATCHED';
CREATE INDEX ix_payos_receipt_attempt_history
    ON payos_webhook_receipts (attempt_id, received_at DESC)
    WHERE attempt_id IS NOT NULL;
CREATE INDEX ix_payos_receipt_order_code
    ON payos_webhook_receipts (merchant_channel_id, order_code, received_at DESC);

CREATE TABLE payos_reconciliation_queries (
    query_id UUID PRIMARY KEY,
    attempt_id UUID NOT NULL,
    merchant_channel_id VARCHAR(128) NOT NULL,
    query_request_id VARCHAR(128) NOT NULL,
    order_code BIGINT NOT NULL,
    payment_link_id VARCHAR(128),
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    outcome VARCHAR(24) NOT NULL,
    provider_status VARCHAR(32),
    amount_vnd BIGINT NOT NULL,
    amount_paid_vnd BIGINT,
    response_digest VARCHAR(64),
    signature_verified BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_payos_query_attempt FOREIGN KEY (attempt_id)
        REFERENCES payment_attempts (attempt_id) ON DELETE RESTRICT,
    CONSTRAINT fk_payos_query_link_identity FOREIGN KEY
        (attempt_id, merchant_channel_id, order_code, payment_link_id, amount_vnd)
        REFERENCES payos_payment_links (attempt_id, merchant_channel_id, order_code, payment_link_id, amount_vnd)
        ON DELETE RESTRICT,
    CONSTRAINT uq_payos_query_channel_request UNIQUE (merchant_channel_id, query_request_id),
    CONSTRAINT ck_payos_query_order_code CHECK (order_code > 0),
    CONSTRAINT ck_payos_query_outcome CHECK (outcome IN ('PENDING', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT ck_payos_query_amount_paid CHECK (amount_paid_vnd IS NULL OR amount_paid_vnd >= 0),
    CONSTRAINT ck_payos_query_amount CHECK (amount_vnd > 0),
    CONSTRAINT ck_payos_query_digest CHECK (response_digest IS NULL OR response_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_payos_query_completed CHECK (
        (outcome = 'PENDING' AND completed_at IS NULL)
        OR (outcome <> 'PENDING' AND completed_at IS NOT NULL)),
    CONSTRAINT ck_payos_query_success_trust CHECK (
        outcome <> 'SUCCEEDED' OR (signature_verified AND response_digest ~ '^[0-9a-f]{64}$'
        AND provider_status = 'PAID' AND amount_paid_vnd = amount_vnd AND payment_link_id IS NOT NULL))
);

CREATE INDEX ix_payos_queries_attempt_history
    ON payos_reconciliation_queries (attempt_id, requested_at DESC);
