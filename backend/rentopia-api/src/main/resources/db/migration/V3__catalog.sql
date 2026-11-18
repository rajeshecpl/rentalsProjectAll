-- =====================================================================
-- V3: catalog — cities, categories, the shared listings table,
-- images, amenities, prices and listing moderation history.
-- Category-specific attributes live in V4 detail tables (1:1 with listings).
-- =====================================================================

CREATE TABLE cities (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name       varchar(120) NOT NULL,
    state      varchar(120) NOT NULL,
    slug       varchar(140) NOT NULL UNIQUE,
    latitude   numeric(9, 6),
    longitude  numeric(9, 6),
    is_active  boolean      NOT NULL DEFAULT true,
    created_at timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (name, state)
);

ALTER TABLE customer_profiles
    ADD CONSTRAINT fk_customer_profiles_city FOREIGN KEY (preferred_city_id) REFERENCES cities (id);

CREATE TABLE categories (
    id          smallint PRIMARY KEY,
    code        varchar(30)  NOT NULL UNIQUE,
    name        varchar(80)  NOT NULL,
    slug        varchar(80)  NOT NULL UNIQUE,
    sort_order  smallint     NOT NULL DEFAULT 0,
    is_active   boolean      NOT NULL DEFAULT true
);

INSERT INTO categories (id, code, name, slug, sort_order) VALUES
    (1, 'PROPERTY', 'Properties',   'properties', 1),
    (2, 'ROOM',     'Rooms',        'rooms',      2),
    (3, 'HOSTEL',   'Hostels / PG', 'hostels',    3),
    (4, 'CAR',      'Cars',         'cars',       4),
    (5, 'BIKE',     'Bikes',        'bikes',      5);

-- ---------------------------------------------------------------------
-- listings: common fields for every category
-- booking_mode lets each listing choose instant booking, vendor approval,
-- or enquiry-only — an open business decision kept configurable.
-- ---------------------------------------------------------------------
CREATE TABLE listings (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    vendor_id       uuid         NOT NULL REFERENCES vendor_profiles (id) ON DELETE RESTRICT,
    category_id     smallint     NOT NULL REFERENCES categories (id),
    city_id         uuid         NOT NULL REFERENCES cities (id),
    title           varchar(200) NOT NULL,
    slug            varchar(240) NOT NULL UNIQUE,
    description     text,
    status          text         NOT NULL DEFAULT 'DRAFT'
                    CHECK (status IN ('DRAFT', 'PENDING_REVIEW', 'PUBLISHED', 'REJECTED', 'SUSPENDED', 'ARCHIVED')),
    booking_mode    text         NOT NULL DEFAULT 'REQUEST'
                    CHECK (booking_mode IN ('INSTANT', 'REQUEST', 'ENQUIRY')),
    address_line1   varchar(200),
    address_line2   varchar(200),
    locality        varchar(120),
    pincode         varchar(10),
    latitude        numeric(9, 6),
    longitude       numeric(9, 6),
    rules           text,
    cancellation_policy text     NOT NULL DEFAULT 'MODERATE'
                    CHECK (cancellation_policy IN ('FLEXIBLE', 'MODERATE', 'STRICT', 'NON_REFUNDABLE')),
    is_featured     boolean      NOT NULL DEFAULT false,
    featured_until  timestamptz,
    rating_avg      numeric(3, 2) NOT NULL DEFAULT 0,
    rating_count    integer      NOT NULL DEFAULT 0,
    published_at    timestamptz,
    version         bigint       NOT NULL DEFAULT 0,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chk_listings_lat CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    CONSTRAINT chk_listings_lng CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180)
);
CREATE INDEX idx_listings_vendor ON listings (vendor_id);
-- Main search path: published listings by city + category
CREATE INDEX idx_listings_search ON listings (city_id, category_id, published_at DESC) WHERE status = 'PUBLISHED';
CREATE INDEX idx_listings_status ON listings (status);
CREATE INDEX idx_listings_pincode ON listings (pincode) WHERE status = 'PUBLISHED';
CREATE TRIGGER trg_listings_updated BEFORE UPDATE ON listings
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE listing_images (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id  uuid         NOT NULL REFERENCES listings (id) ON DELETE CASCADE,
    s3_key      varchar(500) NOT NULL,
    caption     varchar(200),
    sort_order  smallint     NOT NULL DEFAULT 0,
    is_cover    boolean      NOT NULL DEFAULT false,
    width       integer,
    height      integer,
    created_at  timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_listing_images_listing ON listing_images (listing_id, sort_order);
CREATE UNIQUE INDEX uq_listing_images_cover ON listing_images (listing_id) WHERE is_cover;

CREATE TABLE amenities (
    id           smallserial PRIMARY KEY,
    code         varchar(50)  NOT NULL UNIQUE,
    name         varchar(100) NOT NULL,
    -- NULL = applies to all categories
    category_id  smallint REFERENCES categories (id)
);

INSERT INTO amenities (code, name, category_id) VALUES
    ('WIFI', 'Wi-Fi', NULL),
    ('AC', 'Air conditioning', NULL),
    ('PARKING', 'Parking', NULL),
    ('POWER_BACKUP', 'Power backup', NULL),
    ('LIFT', 'Lift', 1),
    ('SECURITY', '24x7 security', NULL),
    ('GYM', 'Gym', 1),
    ('LAUNDRY', 'Laundry', 3),
    ('MEALS', 'Meals included', 3),
    ('HOUSEKEEPING', 'Housekeeping', NULL),
    ('HELMET', 'Helmet provided', 5),
    ('GPS', 'GPS', 4),
    ('CHILD_SEAT', 'Child seat', 4);

CREATE TABLE listing_amenities (
    listing_id  uuid     NOT NULL REFERENCES listings (id) ON DELETE CASCADE,
    amenity_id  smallint NOT NULL REFERENCES amenities (id),
    PRIMARY KEY (listing_id, amenity_id)
);
CREATE INDEX idx_listing_amenities_amenity ON listing_amenities (amenity_id);

-- One row per billing period a listing supports (e.g. a bike: HOURLY + DAILY).
CREATE TABLE listing_prices (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id         uuid     NOT NULL REFERENCES listings (id) ON DELETE CASCADE,
    billing_period     text     NOT NULL
                       CHECK (billing_period IN ('HOURLY', 'DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY')),
    amount_paise       bigint   NOT NULL CHECK (amount_paise > 0),
    security_deposit_paise bigint NOT NULL DEFAULT 0 CHECK (security_deposit_paise >= 0),
    currency           char(3)  NOT NULL DEFAULT 'INR',
    min_units          integer  NOT NULL DEFAULT 1 CHECK (min_units >= 1),
    max_units          integer  CHECK (max_units IS NULL OR max_units >= min_units),
    is_active          boolean  NOT NULL DEFAULT true,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_listing_prices_active_period ON listing_prices (listing_id, billing_period) WHERE is_active;
CREATE TRIGGER trg_listing_prices_updated BEFORE UPDATE ON listing_prices
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE listing_status_history (
    id           bigserial PRIMARY KEY,
    listing_id   uuid        NOT NULL REFERENCES listings (id) ON DELETE CASCADE,
    from_status  text,
    to_status    text        NOT NULL,
    changed_by   uuid REFERENCES users (id),
    reason       varchar(500),
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_listing_status_history_listing ON listing_status_history (listing_id, created_at);
