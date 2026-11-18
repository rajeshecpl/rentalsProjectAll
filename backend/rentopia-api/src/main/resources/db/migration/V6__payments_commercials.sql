-- =====================================================================
-- V6: payments, refunds, commissions, coupons and vendor settlements.
-- No card data is ever stored; only gateway references.
-- Webhook idempotency: payment_events.gateway_event_id is UNIQUE, so a
-- duplicate delivery fails the insert and is skipped.
-- =====================================================================

CREATE TABLE payments (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id          uuid         NOT NULL REFERENCES bookings (id) ON DELETE RESTRICT,
    gateway             varchar(30)  NOT NULL,
    gateway_order_id    varchar(100) NOT NULL UNIQUE,
    gateway_payment_id  varchar(100) UNIQUE,
    amount_paise        bigint       NOT NULL CHECK (amount_paise > 0),
    currency            char(3)      NOT NULL DEFAULT 'INR',
    status              text         NOT NULL DEFAULT 'CREATED'
                        CHECK (status IN ('CREATED', 'AUTHORIZED', 'CAPTURED', 'FAILED',
                                          'PARTIALLY_REFUNDED', 'REFUNDED')),
    method              varchar(30),
    failure_code        varchar(100),
    failure_reason      varchar(500),
    captured_at         timestamptz,
    version             bigint       NOT NULL DEFAULT 0,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_payments_booking ON payments (booking_id);
CREATE INDEX idx_payments_status ON payments (status, created_at);
CREATE TRIGGER trg_payments_updated BEFORE UPDATE ON payments
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE payment_events (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    gateway           varchar(30)  NOT NULL,
    gateway_event_id  varchar(120) NOT NULL,
    event_type        varchar(80)  NOT NULL,
    payment_id        uuid REFERENCES payments (id),
    signature_valid   boolean      NOT NULL,
    payload           jsonb        NOT NULL,
    received_at       timestamptz  NOT NULL DEFAULT now(),
    processed_at      timestamptz,
    processing_error  varchar(1000),
    CONSTRAINT uq_payment_events_gateway_event UNIQUE (gateway, gateway_event_id)
);
CREATE INDEX idx_payment_events_payment ON payment_events (payment_id);
CREATE INDEX idx_payment_events_unprocessed ON payment_events (received_at) WHERE processed_at IS NULL;

CREATE TABLE refunds (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id         uuid         NOT NULL REFERENCES payments (id) ON DELETE RESTRICT,
    booking_id         uuid         NOT NULL REFERENCES bookings (id) ON DELETE RESTRICT,
    gateway_refund_id  varchar(100) UNIQUE,
    amount_paise       bigint       NOT NULL CHECK (amount_paise > 0),
    reason             text         NOT NULL
                       CHECK (reason IN ('CUSTOMER_CANCELLED', 'VENDOR_REJECTED', 'VENDOR_CANCELLED',
                                         'HOLD_EXPIRED', 'DEPOSIT_RETURN', 'DISPUTE', 'OTHER')),
    status             text         NOT NULL DEFAULT 'PENDING'
                       CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
    idempotency_key    varchar(80)  NOT NULL UNIQUE,
    initiated_by       uuid REFERENCES users (id),
    failure_reason     varchar(500),
    processed_at       timestamptz,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_refunds_payment ON refunds (payment_id);
CREATE INDEX idx_refunds_booking ON refunds (booking_id);
CREATE TRIGGER trg_refunds_updated BEFORE UPDATE ON refunds
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Most specific rule wins: vendor+category > vendor > category > global default.
CREATE TABLE commission_rules (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id      smallint REFERENCES categories (id),
    vendor_id        uuid REFERENCES vendor_profiles (id) ON DELETE CASCADE,
    rate_bps         integer     NOT NULL CHECK (rate_bps BETWEEN 0 AND 10000),  -- 800 = 8%
    flat_fee_paise   bigint      NOT NULL DEFAULT 0 CHECK (flat_fee_paise >= 0),
    effective_from   timestamptz NOT NULL DEFAULT now(),
    effective_to     timestamptz,
    created_by       uuid REFERENCES users (id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_commission_rules_window CHECK (effective_to IS NULL OR effective_to > effective_from)
);
CREATE INDEX idx_commission_rules_lookup ON commission_rules (vendor_id, category_id, effective_from DESC);

CREATE TABLE coupons (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code                citext       NOT NULL UNIQUE,
    description         varchar(300),
    discount_type       text         NOT NULL CHECK (discount_type IN ('PERCENT', 'FLAT')),
    discount_value      bigint       NOT NULL CHECK (discount_value > 0),  -- bps for PERCENT, paise for FLAT
    max_discount_paise  bigint       CHECK (max_discount_paise IS NULL OR max_discount_paise > 0),
    min_order_paise     bigint       NOT NULL DEFAULT 0,
    category_id         smallint REFERENCES categories (id),
    city_id             uuid REFERENCES cities (id),
    usage_limit_total   integer      CHECK (usage_limit_total IS NULL OR usage_limit_total > 0),
    usage_limit_per_user integer     NOT NULL DEFAULT 1 CHECK (usage_limit_per_user > 0),
    valid_from          timestamptz  NOT NULL,
    valid_to            timestamptz  NOT NULL,
    is_active           boolean      NOT NULL DEFAULT true,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chk_coupons_window CHECK (valid_to > valid_from),
    CONSTRAINT chk_coupons_percent CHECK (discount_type <> 'PERCENT' OR discount_value <= 10000)
);

ALTER TABLE bookings
    ADD CONSTRAINT fk_bookings_coupon FOREIGN KEY (coupon_id) REFERENCES coupons (id);

CREATE TABLE coupon_redemptions (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    coupon_id       uuid        NOT NULL REFERENCES coupons (id),
    user_id         uuid        NOT NULL REFERENCES users (id),
    booking_id      uuid        NOT NULL UNIQUE REFERENCES bookings (id) ON DELETE CASCADE,
    discount_paise  bigint      NOT NULL CHECK (discount_paise > 0),
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_coupon_redemptions_coupon_user ON coupon_redemptions (coupon_id, user_id);

-- A settlement batches what RENTOPIA owes a vendor for a period.
-- The payout schedule (after completion, after check-in, weekly…) is a
-- business decision; this table works for any of them.
CREATE TABLE vendor_settlements (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    vendor_id              uuid         NOT NULL REFERENCES vendor_profiles (id) ON DELETE RESTRICT,
    bank_account_id        uuid REFERENCES vendor_bank_accounts (id),
    period_start           date         NOT NULL,
    period_end             date         NOT NULL,
    gross_paise            bigint       NOT NULL DEFAULT 0,
    commission_paise       bigint       NOT NULL DEFAULT 0,
    adjustments_paise      bigint       NOT NULL DEFAULT 0,
    net_payout_paise       bigint       NOT NULL DEFAULT 0,
    currency               char(3)      NOT NULL DEFAULT 'INR',
    status                 text         NOT NULL DEFAULT 'PENDING'
                           CHECK (status IN ('PENDING', 'PROCESSING', 'PAID', 'FAILED', 'ON_HOLD')),
    gateway_transfer_id    varchar(100) UNIQUE,
    paid_at                timestamptz,
    created_at             timestamptz  NOT NULL DEFAULT now(),
    updated_at             timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chk_vendor_settlements_period CHECK (period_end >= period_start),
    CONSTRAINT chk_vendor_settlements_net CHECK (
        net_payout_paise = gross_paise - commission_paise + adjustments_paise)
);
CREATE INDEX idx_vendor_settlements_vendor ON vendor_settlements (vendor_id, period_end DESC);
CREATE TRIGGER trg_vendor_settlements_updated BEFORE UPDATE ON vendor_settlements
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- A booking is settled at most once.
CREATE TABLE settlement_items (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    settlement_id      uuid   NOT NULL REFERENCES vendor_settlements (id) ON DELETE CASCADE,
    booking_id         uuid   NOT NULL UNIQUE REFERENCES bookings (id) ON DELETE RESTRICT,
    gross_paise        bigint NOT NULL,
    commission_paise   bigint NOT NULL,
    net_paise          bigint NOT NULL,
    CONSTRAINT chk_settlement_items_net CHECK (net_paise = gross_paise - commission_paise)
);
CREATE INDEX idx_settlement_items_settlement ON settlement_items (settlement_id);
