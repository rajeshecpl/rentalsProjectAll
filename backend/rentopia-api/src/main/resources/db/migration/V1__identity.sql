-- =====================================================================
-- V1: extensions, shared helpers, identity (users, roles, tokens, OTP)
-- Conventions:
--   * UUID primary keys (gen_random_uuid)
--   * timestamptz everywhere; created_at / updated_at on every table
--   * money = bigint paise + currency char(3)
--   * enums = text + CHECK (easy to extend in later migrations)
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Keeps updated_at current on UPDATE. Attached to every mutable table.
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------
CREATE TABLE users (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email              citext      NOT NULL UNIQUE,
    phone              varchar(20) UNIQUE,
    password_hash      varchar(255) NOT NULL,
    full_name          varchar(150) NOT NULL,
    status             text        NOT NULL DEFAULT 'ACTIVE'
                       CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED', 'DELETED')),
    email_verified_at  timestamptz,
    phone_verified_at  timestamptz,
    last_login_at      timestamptz,
    version            bigint      NOT NULL DEFAULT 0,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_users_updated BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------
-- roles / user_roles
-- ---------------------------------------------------------------------
CREATE TABLE roles (
    id          smallint PRIMARY KEY,
    code        varchar(30) NOT NULL UNIQUE,
    description varchar(200)
);

INSERT INTO roles (id, code, description) VALUES
    (1, 'CUSTOMER',    'Searches and books rentals'),
    (2, 'VENDOR',      'Publishes and manages listings'),
    (3, 'ADMIN',       'Operates the platform'),
    (4, 'SUPER_ADMIN', 'Manages admins and system configuration'),
    (5, 'SUPPORT',     'Handles complaints and customer support');

CREATE TABLE user_roles (
    user_id    uuid     NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id    smallint NOT NULL REFERENCES roles (id),
    granted_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role_id)
);
CREATE INDEX idx_user_roles_role ON user_roles (role_id);

-- ---------------------------------------------------------------------
-- refresh_tokens: only the SHA-256 hash of the token is stored.
-- Rotation: on refresh, the old row is revoked and replaced_by points to the new one.
-- ---------------------------------------------------------------------
CREATE TABLE refresh_tokens (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash   varchar(64) NOT NULL UNIQUE,
    expires_at   timestamptz NOT NULL,
    revoked_at   timestamptz,
    replaced_by  uuid REFERENCES refresh_tokens (id),
    user_agent   varchar(300),
    ip_address   varchar(45),
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);

-- ---------------------------------------------------------------------
-- otp_codes: hashed one-time codes for phone/email verification & reset
-- ---------------------------------------------------------------------
CREATE TABLE otp_codes (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid REFERENCES users (id) ON DELETE CASCADE,
    channel      text        NOT NULL CHECK (channel IN ('SMS', 'EMAIL')),
    destination  varchar(150) NOT NULL,
    purpose      text        NOT NULL
                 CHECK (purpose IN ('VERIFY_PHONE', 'VERIFY_EMAIL', 'LOGIN', 'PASSWORD_RESET')),
    code_hash    varchar(64) NOT NULL,
    attempts     smallint    NOT NULL DEFAULT 0,
    max_attempts smallint    NOT NULL DEFAULT 5,
    expires_at   timestamptz NOT NULL,
    consumed_at  timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_otp_codes_lookup ON otp_codes (destination, purpose, created_at DESC);
CREATE INDEX idx_otp_codes_user ON otp_codes (user_id);

-- ---------------------------------------------------------------------
-- customer_profiles (1:1 with users)
-- ---------------------------------------------------------------------
CREATE TABLE customer_profiles (
    user_id        uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    date_of_birth  date,
    gender         text CHECK (gender IN ('MALE', 'FEMALE', 'OTHER', 'UNDISCLOSED')),
    avatar_s3_key  varchar(500),
    preferred_city_id uuid,  -- FK added in V3 once cities exists
    version        bigint      NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_customer_profiles_updated BEFORE UPDATE ON customer_profiles
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------
-- addresses (a user may have several)
-- ---------------------------------------------------------------------
CREATE TABLE addresses (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    label         varchar(50),
    line1         varchar(200) NOT NULL,
    line2         varchar(200),
    locality      varchar(120),
    city          varchar(120) NOT NULL,
    state         varchar(120) NOT NULL,
    pincode       varchar(10)  NOT NULL,
    country       char(2)      NOT NULL DEFAULT 'IN',
    is_default    boolean      NOT NULL DEFAULT false,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_addresses_user ON addresses (user_id);
CREATE UNIQUE INDEX uq_addresses_one_default ON addresses (user_id) WHERE is_default;
CREATE TRIGGER trg_addresses_updated BEFORE UPDATE ON addresses
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
