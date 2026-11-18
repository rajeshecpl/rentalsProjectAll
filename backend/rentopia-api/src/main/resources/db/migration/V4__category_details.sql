-- =====================================================================
-- V4: category-specific detail tables, each 1:1 with listings.
-- listing_id is both PK and FK, so a listing has at most one detail row.
-- =====================================================================

-- PROPERTY: whole flats/houses/commercial units on monthly/yearly lease
CREATE TABLE properties (
    listing_id          uuid PRIMARY KEY REFERENCES listings (id) ON DELETE CASCADE,
    property_type       text     NOT NULL
                        CHECK (property_type IN ('APARTMENT', 'INDEPENDENT_HOUSE', 'VILLA', 'STUDIO',
                                                 'PENTHOUSE', 'OFFICE', 'SHOP', 'WAREHOUSE', 'PLOT')),
    bhk                 smallint CHECK (bhk IS NULL OR bhk BETWEEN 0 AND 20),
    bathrooms           smallint CHECK (bathrooms IS NULL OR bathrooms >= 0),
    area_sqft           integer  CHECK (area_sqft IS NULL OR area_sqft > 0),
    floor_number        smallint,
    total_floors        smallint,
    furnishing          text     NOT NULL DEFAULT 'UNFURNISHED'
                        CHECK (furnishing IN ('UNFURNISHED', 'SEMI_FURNISHED', 'FULLY_FURNISHED')),
    tenant_preference   text     NOT NULL DEFAULT 'ANY'
                        CHECK (tenant_preference IN ('ANY', 'FAMILY', 'BACHELOR_MALE', 'BACHELOR_FEMALE', 'COMPANY')),
    parking             text     NOT NULL DEFAULT 'NONE'
                        CHECK (parking IN ('NONE', 'BIKE', 'CAR', 'BOTH')),
    available_from      date,
    notice_period_days  smallint CHECK (notice_period_days IS NULL OR notice_period_days >= 0),
    lock_in_months      smallint CHECK (lock_in_months IS NULL OR lock_in_months >= 0),
    maintenance_paise   bigint   CHECK (maintenance_paise IS NULL OR maintenance_paise >= 0)
);
CREATE INDEX idx_properties_filter ON properties (property_type, bhk, furnishing);

-- ROOM: a single private/shared room rented as one unit
CREATE TABLE rooms (
    listing_id         uuid PRIMARY KEY REFERENCES listings (id) ON DELETE CASCADE,
    sharing_type       text     NOT NULL CHECK (sharing_type IN ('PRIVATE', 'SHARED')),
    capacity           smallint NOT NULL CHECK (capacity BETWEEN 1 AND 20),
    attached_bathroom  boolean  NOT NULL DEFAULT false,
    has_ac             boolean  NOT NULL DEFAULT false,
    has_wifi           boolean  NOT NULL DEFAULT false,
    furnishing         text     NOT NULL DEFAULT 'UNFURNISHED'
                       CHECK (furnishing IN ('UNFURNISHED', 'SEMI_FURNISHED', 'FULLY_FURNISHED')),
    tenant_preference  text     NOT NULL DEFAULT 'ANY'
                       CHECK (tenant_preference IN ('ANY', 'MALE', 'FEMALE', 'FAMILY'))
);

-- HOSTEL / PG: a building with rooms, each room with beds
CREATE TABLE hostels (
    listing_id        uuid PRIMARY KEY REFERENCES listings (id) ON DELETE CASCADE,
    gender_type       text     NOT NULL CHECK (gender_type IN ('BOYS', 'GIRLS', 'COLIVING')),
    food_included     boolean  NOT NULL DEFAULT false,
    meals_per_day     smallint CHECK (meals_per_day IS NULL OR meals_per_day BETWEEN 0 AND 4),
    curfew_time       time,
    total_rooms       smallint CHECK (total_rooms IS NULL OR total_rooms >= 0),
    notice_period_days smallint CHECK (notice_period_days IS NULL OR notice_period_days >= 0)
);

CREATE TABLE hostel_rooms (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    hostel_listing_id  uuid        NOT NULL REFERENCES hostels (listing_id) ON DELETE CASCADE,
    room_label         varchar(30) NOT NULL,
    sharing_capacity   smallint    NOT NULL CHECK (sharing_capacity BETWEEN 1 AND 20),
    has_ac             boolean     NOT NULL DEFAULT false,
    attached_bathroom  boolean     NOT NULL DEFAULT false,
    floor_number       smallint,
    is_active          boolean     NOT NULL DEFAULT true,
    UNIQUE (hostel_listing_id, room_label)
);

CREATE TABLE beds (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    hostel_room_id  uuid        NOT NULL REFERENCES hostel_rooms (id) ON DELETE CASCADE,
    bed_label       varchar(30) NOT NULL,
    is_active       boolean     NOT NULL DEFAULT true,
    UNIQUE (hostel_room_id, bed_label)
);

-- CAR / BIKE / SCOOTER: one listing = one physical vehicle
CREATE TABLE vehicles (
    listing_id           uuid PRIMARY KEY REFERENCES listings (id) ON DELETE CASCADE,
    vehicle_type         text        NOT NULL CHECK (vehicle_type IN ('CAR', 'MOTORCYCLE', 'SCOOTER')),
    brand                varchar(60) NOT NULL,
    model                varchar(80) NOT NULL,
    variant              varchar(80),
    manufacture_year     smallint    CHECK (manufacture_year IS NULL OR manufacture_year BETWEEN 1990 AND 2100),
    fuel_type            text        NOT NULL
                         CHECK (fuel_type IN ('PETROL', 'DIESEL', 'CNG', 'ELECTRIC', 'HYBRID')),
    transmission         text        CHECK (transmission IN ('MANUAL', 'AUTOMATIC')),
    seats                smallint    CHECK (seats IS NULL OR seats BETWEEN 1 AND 60),
    engine_cc            integer     CHECK (engine_cc IS NULL OR engine_cc > 0),
    registration_number  varchar(20) NOT NULL UNIQUE,
    drive_mode           text        NOT NULL DEFAULT 'SELF'
                         CHECK (drive_mode IN ('SELF', 'CHAUFFEUR', 'BOTH')),
    km_limit_per_day     integer     CHECK (km_limit_per_day IS NULL OR km_limit_per_day > 0),
    extra_km_paise       bigint      CHECK (extra_km_paise IS NULL OR extra_km_paise >= 0),
    min_driver_age       smallint    CHECK (min_driver_age IS NULL OR min_driver_age BETWEEN 16 AND 80),
    pickup_instructions  text
);
CREATE INDEX idx_vehicles_filter ON vehicles (vehicle_type, brand, fuel_type, transmission);

CREATE TABLE vehicle_documents (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    vehicle_listing_id  uuid         NOT NULL REFERENCES vehicles (listing_id) ON DELETE CASCADE,
    doc_type            text         NOT NULL
                        CHECK (doc_type IN ('RC', 'INSURANCE', 'PUC', 'PERMIT', 'FITNESS', 'OTHER')),
    s3_key              varchar(500) NOT NULL,
    valid_until         date,
    status              text         NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')),
    reviewed_by         uuid REFERENCES users (id),
    reviewed_at         timestamptz,
    created_at          timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_vehicle_documents_vehicle ON vehicle_documents (vehicle_listing_id);
CREATE INDEX idx_vehicle_documents_expiry ON vehicle_documents (valid_until) WHERE status = 'APPROVED';
