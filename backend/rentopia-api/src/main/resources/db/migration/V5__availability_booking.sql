-- =====================================================================
-- V5: inventory, availability and bookings.
--
-- Double-booking prevention:
--   * Every bookable thing is one row in inventory_units
--     (a car, a bike, a whole property, a room, or one hostel BED).
--   * A booking reserves one or more units via booking_items, each with a
--     half-open period [start, end).
--   * An EXCLUSION constraint rejects two *blocking* items on the same unit
--     whose periods overlap. PostgreSQL enforces this atomically, so two
--     concurrent requests can never both succeed.
--   * When a booking is cancelled/expired/rejected, the service sets
--     is_blocking = false on its items in the same transaction, freeing them.
--   * Vendor-blocked dates (maintenance, offline booking) live in
--     blocked_dates; triggers stop a booking item and a block overlapping
--     on the same unit. Both triggers lock the inventory_units row first so
--     the cross-table check is race-free.
-- =====================================================================

CREATE TABLE inventory_units (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id  uuid        NOT NULL REFERENCES listings (id) ON DELETE CASCADE,
    unit_kind   text        NOT NULL CHECK (unit_kind IN ('WHOLE', 'VEHICLE', 'ROOM', 'BED')),
    bed_id      uuid UNIQUE REFERENCES beds (id) ON DELETE CASCADE,
    label       varchar(60),
    is_active   boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_inventory_units_bed CHECK ((unit_kind = 'BED') = (bed_id IS NOT NULL))
);
CREATE INDEX idx_inventory_units_listing ON inventory_units (listing_id) WHERE is_active;

CREATE TABLE blocked_dates (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    inventory_unit_id  uuid        NOT NULL REFERENCES inventory_units (id) ON DELETE CASCADE,
    period             tstzrange   NOT NULL CHECK (NOT isempty(period) AND lower_inf(period) = false AND upper_inf(period) = false),
    reason             text        NOT NULL DEFAULT 'VENDOR_BLOCK'
                       CHECK (reason IN ('VENDOR_BLOCK', 'MAINTENANCE', 'OFFLINE_BOOKING', 'ADMIN_HOLD')),
    note               varchar(300),
    created_by         uuid REFERENCES users (id),
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ex_blocked_dates_overlap EXCLUDE USING gist (inventory_unit_id WITH =, period WITH &&)
);

-- ---------------------------------------------------------------------
-- bookings
-- ---------------------------------------------------------------------
CREATE SEQUENCE booking_number_seq START 100001;

CREATE TABLE bookings (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_number         varchar(20)  NOT NULL UNIQUE
                           DEFAULT ('RTP' || nextval('booking_number_seq')),
    customer_id            uuid         NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    listing_id             uuid         NOT NULL REFERENCES listings (id) ON DELETE RESTRICT,
    vendor_id              uuid         NOT NULL REFERENCES vendor_profiles (id) ON DELETE RESTRICT,
    status                 text         NOT NULL DEFAULT 'DRAFT'
                           CHECK (status IN ('DRAFT', 'PENDING_PAYMENT', 'PENDING_VENDOR_APPROVAL', 'CONFIRMED',
                                             'IN_PROGRESS', 'COMPLETED', 'REJECTED', 'EXPIRED', 'CANCELLED',
                                             'PAYMENT_FAILED', 'REFUND_PENDING', 'REFUNDED')),
    start_at               timestamptz  NOT NULL,
    end_at                 timestamptz  NOT NULL,
    billing_period         text         NOT NULL
                           CHECK (billing_period IN ('HOURLY', 'DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY')),
    units_count            integer      NOT NULL DEFAULT 1 CHECK (units_count >= 1),
    guests_count           smallint     CHECK (guests_count IS NULL OR guests_count >= 1),
    -- price snapshot at booking time (paise)
    currency               char(3)      NOT NULL DEFAULT 'INR',
    rent_paise             bigint       NOT NULL DEFAULT 0 CHECK (rent_paise >= 0),
    deposit_paise          bigint       NOT NULL DEFAULT 0 CHECK (deposit_paise >= 0),
    platform_fee_paise     bigint       NOT NULL DEFAULT 0 CHECK (platform_fee_paise >= 0),
    tax_paise              bigint       NOT NULL DEFAULT 0 CHECK (tax_paise >= 0),
    discount_paise         bigint       NOT NULL DEFAULT 0 CHECK (discount_paise >= 0),
    total_paise            bigint       NOT NULL DEFAULT 0 CHECK (total_paise >= 0),
    commission_paise       bigint       NOT NULL DEFAULT 0 CHECK (commission_paise >= 0),
    vendor_payout_paise    bigint       NOT NULL DEFAULT 0 CHECK (vendor_payout_paise >= 0),
    coupon_id              uuid,        -- FK added in V6
    hold_expires_at        timestamptz,
    idempotency_key        varchar(80),
    customer_note          varchar(1000),
    cancelled_by           uuid REFERENCES users (id),
    cancelled_at           timestamptz,
    cancellation_reason    varchar(500),
    confirmed_at           timestamptz,
    completed_at           timestamptz,
    version                bigint       NOT NULL DEFAULT 0,
    created_at             timestamptz  NOT NULL DEFAULT now(),
    updated_at             timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chk_bookings_period CHECK (end_at > start_at),
    CONSTRAINT chk_bookings_total CHECK (
        total_paise = rent_paise + deposit_paise + platform_fee_paise + tax_paise - discount_paise),
    CONSTRAINT chk_bookings_hold CHECK (status <> 'PENDING_PAYMENT' OR hold_expires_at IS NOT NULL),
    CONSTRAINT uq_bookings_idempotency UNIQUE (customer_id, idempotency_key)
);
CREATE INDEX idx_bookings_customer ON bookings (customer_id, created_at DESC);
CREATE INDEX idx_bookings_vendor ON bookings (vendor_id, status, start_at);
CREATE INDEX idx_bookings_listing ON bookings (listing_id, start_at);
-- For the hold-expiry job
CREATE INDEX idx_bookings_hold_expiry ON bookings (hold_expires_at) WHERE status = 'PENDING_PAYMENT';
CREATE TRIGGER trg_bookings_updated BEFORE UPDATE ON bookings
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE booking_items (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id         uuid        NOT NULL REFERENCES bookings (id) ON DELETE CASCADE,
    inventory_unit_id  uuid        NOT NULL REFERENCES inventory_units (id) ON DELETE RESTRICT,
    period             tstzrange   NOT NULL CHECK (NOT isempty(period) AND lower_inf(period) = false AND upper_inf(period) = false),
    is_blocking        boolean     NOT NULL DEFAULT true,
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_booking_items_unit UNIQUE (booking_id, inventory_unit_id),
    -- THE double-booking guard
    CONSTRAINT ex_booking_items_overlap
        EXCLUDE USING gist (inventory_unit_id WITH =, period WITH &&) WHERE (is_blocking)
);
CREATE INDEX idx_booking_items_booking ON booking_items (booking_id);

-- Cross-table guard: booking item vs vendor block on the same unit.
CREATE OR REPLACE FUNCTION check_booking_item_vs_blocks() RETURNS trigger AS $$
BEGIN
    IF NEW.is_blocking THEN
        PERFORM 1 FROM inventory_units WHERE id = NEW.inventory_unit_id FOR UPDATE;
        IF EXISTS (SELECT 1 FROM blocked_dates b
                   WHERE b.inventory_unit_id = NEW.inventory_unit_id
                     AND b.period && NEW.period) THEN
            RAISE EXCEPTION 'Inventory unit % is blocked for the requested period', NEW.inventory_unit_id
                USING ERRCODE = '23P01';  -- exclusion_violation, same as the overlap constraint
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_booking_items_vs_blocks BEFORE INSERT OR UPDATE OF period, is_blocking, inventory_unit_id
    ON booking_items FOR EACH ROW EXECUTE FUNCTION check_booking_item_vs_blocks();

CREATE OR REPLACE FUNCTION check_block_vs_booking_items() RETURNS trigger AS $$
BEGIN
    PERFORM 1 FROM inventory_units WHERE id = NEW.inventory_unit_id FOR UPDATE;
    IF EXISTS (SELECT 1 FROM booking_items i
               WHERE i.inventory_unit_id = NEW.inventory_unit_id
                 AND i.is_blocking
                 AND i.period && NEW.period) THEN
        RAISE EXCEPTION 'Inventory unit % already has a booking in the requested period', NEW.inventory_unit_id
            USING ERRCODE = '23P01';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_blocked_dates_vs_bookings BEFORE INSERT OR UPDATE OF period, inventory_unit_id
    ON blocked_dates FOR EACH ROW EXECUTE FUNCTION check_block_vs_booking_items();

-- Itemised price breakdown shown on the quote / invoice
CREATE TABLE booking_pricing_lines (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id    uuid         NOT NULL REFERENCES bookings (id) ON DELETE CASCADE,
    line_type     text         NOT NULL
                  CHECK (line_type IN ('RENT', 'DEPOSIT', 'PLATFORM_FEE', 'TAX', 'DISCOUNT', 'EXTRA_CHARGE')),
    description   varchar(200) NOT NULL,
    quantity      numeric(10, 2) NOT NULL DEFAULT 1,
    unit_paise    bigint       NOT NULL,
    amount_paise  bigint       NOT NULL,
    sort_order    smallint     NOT NULL DEFAULT 0
);
CREATE INDEX idx_booking_pricing_lines_booking ON booking_pricing_lines (booking_id);

CREATE TABLE booking_status_history (
    id           bigserial PRIMARY KEY,
    booking_id   uuid        NOT NULL REFERENCES bookings (id) ON DELETE CASCADE,
    from_status  text,
    to_status    text        NOT NULL,
    changed_by   uuid REFERENCES users (id),
    reason       varchar(500),
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_booking_status_history_booking ON booking_status_history (booking_id, created_at);
