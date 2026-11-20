-- Payment Attempt expand migration. V1-V10 are owned by existing releases/AI-01.
-- Preserve legacy Payment/Order values; only add nullable monetary snapshots and attempt history.

ALTER TABLE orders
    ADD COLUMN shipping_amount_vnd BIGINT,
    ADD COLUMN shipping_confirmation_reference VARCHAR(255),
    ADD COLUMN payable_amount_vnd BIGINT,
    ADD COLUMN payment_total_frozen_at TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT ck_orders_shipping_amount_vnd
        CHECK (shipping_amount_vnd IS NULL OR shipping_amount_vnd >= 0),
    ADD CONSTRAINT ck_orders_payable_amount_vnd
        CHECK (payable_amount_vnd IS NULL OR payable_amount_vnd > 0),
    ADD CONSTRAINT ck_orders_shipping_snapshot_complete
        CHECK (
            (shipping_amount_vnd IS NULL AND shipping_confirmation_reference IS NULL
                AND payable_amount_vnd IS NULL AND payment_total_frozen_at IS NULL)
            OR (shipping_amount_vnd IS NOT NULL
                AND length(trim(shipping_confirmation_reference)) > 0
                AND payable_amount_vnd IS NOT NULL AND payment_total_frozen_at IS NOT NULL)
        );

-- Existing amount_vnd values are authoritative and are never overwritten. A legacy Double is
-- copied only when it is a positive, whole, exactly representable IEEE-754 integer.
UPDATE payments
SET amount_vnd = CASE
    WHEN amount > 0
        AND amount <= 9007199254740991::DOUBLE PRECISION
        AND amount = trunc(amount)
    THEN amount::BIGINT
    ELSE NULL
END
WHERE amount_vnd IS NULL;

ALTER TABLE orders
    ADD CONSTRAINT uq_orders_id_owner UNIQUE (order_id, user_id);
ALTER TABLE payments
    ADD CONSTRAINT uq_payments_id_order UNIQUE (payment_id, order_id);

CREATE TABLE payment_attempts (
    attempt_id UUID PRIMARY KEY,
    payment_id INTEGER NOT NULL,
    order_id INTEGER NOT NULL,
    owner_id INTEGER NOT NULL,
    provider VARCHAR(16) NOT NULL,
    amount_vnd BIGINT,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(128) NOT NULL,
    provider_request_id VARCHAR(255),
    merchant_reference VARCHAR(255),
    provider_transaction_id VARCHAR(255),
    source_payment_status VARCHAR(16),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    submitted_at TIMESTAMP WITH TIME ZONE,
    last_callback_at TIMESTAMP WITH TIME ZONE,
    resolved_at TIMESTAMP WITH TIME ZONE,
    unknown_since TIMESTAMP WITH TIME ZONE,
    error_code VARCHAR(64),
    error_message VARCHAR(500),
    CONSTRAINT fk_payment_attempt_payment_order FOREIGN KEY (payment_id, order_id)
        REFERENCES payments (payment_id, order_id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_attempt_order_owner FOREIGN KEY (order_id, owner_id)
        REFERENCES orders (order_id, user_id) ON DELETE RESTRICT,
    CONSTRAINT uq_payment_attempt_id_provider UNIQUE (attempt_id, provider),
    CONSTRAINT ck_payment_attempt_provider CHECK (provider IN ('VNPAY', 'MOMO', 'UNKNOWN')),
    CONSTRAINT ck_payment_attempt_status CHECK (status IN (
        'INITIATED', 'SUBMITTING', 'PENDING', 'UNKNOWN', 'CANCELED', 'LEGACY_REVIEW',
        'SUCCEEDED', 'FAILED', 'EXPIRED', 'PARTIALLY_REFUNDED', 'REFUNDED')),
    CONSTRAINT ck_payment_attempt_source_status CHECK (
        source_payment_status IS NULL OR source_payment_status IN ('PENDING', 'SUCCESS', 'FAILED')),
    CONSTRAINT ck_payment_attempt_amount CHECK (
        (amount_vnd IS NOT NULL AND amount_vnd > 0)
        OR (source_payment_status IS NOT NULL AND status IN ('UNKNOWN', 'LEGACY_REVIEW'))),
    CONSTRAINT ck_payment_attempt_legacy_provider CHECK (
        provider <> 'UNKNOWN' OR source_payment_status IS NOT NULL),
    CONSTRAINT ck_payment_attempt_idempotency_key CHECK (length(trim(idempotency_key)) > 0),
    CONSTRAINT ck_payment_attempt_fingerprint CHECK (length(trim(request_fingerprint)) > 0),
    CONSTRAINT ck_payment_attempt_error_code CHECK (error_code IS NULL OR length(trim(error_code)) > 0),
    CONSTRAINT ck_payment_attempt_error_message CHECK (error_message IS NULL OR length(error_message) <= 500)
);

CREATE UNIQUE INDEX uq_payment_attempts_owner_idempotency
    ON payment_attempts (owner_id, idempotency_key);
CREATE UNIQUE INDEX uq_payment_attempts_provider_request
    ON payment_attempts (provider, provider_request_id)
    WHERE provider_request_id IS NOT NULL;
CREATE UNIQUE INDEX uq_payment_attempts_provider_transaction
    ON payment_attempts (provider, provider_transaction_id)
    WHERE provider_transaction_id IS NOT NULL;
CREATE UNIQUE INDEX uq_payment_attempts_one_active_per_order
    ON payment_attempts (order_id)
    WHERE status IN ('INITIATED', 'SUBMITTING', 'PENDING', 'UNKNOWN', 'LEGACY_REVIEW');
CREATE INDEX ix_payment_attempts_order_history
    ON payment_attempts (order_id, created_at DESC, attempt_id DESC);
CREATE INDEX ix_payment_attempts_owner_history
    ON payment_attempts (owner_id, created_at DESC, attempt_id DESC);

-- Backfill an auditable, retry-blocking attempt for every legacy aggregate row.
INSERT INTO payment_attempts (
    attempt_id, payment_id, order_id, owner_id, provider, amount_vnd, status,
    idempotency_key, request_fingerprint, source_payment_status, unknown_since
)
SELECT
    gen_random_uuid(), p.payment_id, p.order_id, o.user_id,
    CASE p.method WHEN 'VNPAY' THEN 'VNPAY' WHEN 'MOMO' THEN 'MOMO' ELSE 'UNKNOWN' END,
    p.amount_vnd,
    CASE p.status WHEN 'PENDING' THEN 'UNKNOWN' ELSE 'LEGACY_REVIEW' END,
    'legacy-payment:' || p.payment_id,
    'legacy-payment:' || p.payment_id,
    p.status,
    CASE WHEN p.status = 'PENDING' THEN CURRENT_TIMESTAMP ELSE NULL END
FROM payments p
JOIN orders o ON o.order_id = p.order_id;

CREATE TABLE payment_callback_events (
    callback_event_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    provider VARCHAR(16) NOT NULL,
    provider_event_key VARCHAR(255) NOT NULL,
    attempt_id UUID NOT NULL,
    event_digest VARCHAR(64) NOT NULL,
    verified_at TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_payment_callback_attempt_provider FOREIGN KEY (attempt_id, provider)
        REFERENCES payment_attempts (attempt_id, provider) ON DELETE RESTRICT,
    CONSTRAINT uq_payment_callback_provider_event UNIQUE (provider, provider_event_key),
    CONSTRAINT ck_payment_callback_provider CHECK (provider IN ('VNPAY', 'MOMO')),
    CONSTRAINT ck_payment_callback_event_key CHECK (length(trim(provider_event_key)) > 0),
    CONSTRAINT ck_payment_callback_digest CHECK (event_digest ~ '^[0-9a-f]{64}$')
);

CREATE TABLE payment_reconciliation_cases (
    case_id UUID PRIMARY KEY,
    attempt_id UUID NOT NULL,
    order_id INTEGER NOT NULL,
    provider VARCHAR(16) NOT NULL,
    provider_event_key VARCHAR(255) NOT NULL,
    event_digest VARCHAR(64) NOT NULL,
    observed_amount_vnd BIGINT NOT NULL CHECK (observed_amount_vnd > 0),
    provider_transaction_id VARCHAR(255),
    verification_source VARCHAR(80) NOT NULL,
    verified_at TIMESTAMP WITH TIME ZONE NOT NULL,
    conflict_reason VARCHAR(500) NOT NULL,
    resolution_state VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_payment_reconciliation_attempt_provider FOREIGN KEY (attempt_id, provider)
        REFERENCES payment_attempts (attempt_id, provider) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_reconciliation_order FOREIGN KEY (order_id)
        REFERENCES orders (order_id) ON DELETE RESTRICT,
    CONSTRAINT uq_payment_reconciliation_event UNIQUE (attempt_id, provider, provider_event_key),
    CONSTRAINT ck_payment_reconciliation_provider CHECK (provider IN ('VNPAY', 'MOMO')),
    CONSTRAINT ck_payment_reconciliation_digest CHECK (event_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_payment_reconciliation_source CHECK (length(trim(verification_source)) > 0),
    CONSTRAINT ck_payment_reconciliation_reason CHECK (length(trim(conflict_reason)) > 0),
    CONSTRAINT ck_payment_reconciliation_resolution CHECK (resolution_state IN ('OPEN', 'RESOLVED')),
    CONSTRAINT ck_payment_reconciliation_resolved_at CHECK (
        (resolution_state = 'OPEN' AND resolved_at IS NULL)
        OR (resolution_state = 'RESOLVED' AND resolved_at IS NOT NULL))
);

CREATE UNIQUE INDEX uq_payment_reconciliation_open_order
    ON payment_reconciliation_cases (order_id)
    WHERE resolution_state = 'OPEN';
CREATE INDEX ix_payment_reconciliation_attempt
    ON payment_reconciliation_cases (attempt_id, created_at DESC);
