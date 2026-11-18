-- =====================================================================
-- V2: vendors (individual owners and registered rental businesses)
-- Sensitive identifiers (PAN, bank account numbers) are NOT stored in full;
-- only last-4 digits plus the payment gateway's linked-account reference.
-- =====================================================================

CREATE TABLE vendor_profiles (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              uuid        NOT NULL UNIQUE REFERENCES users (id) ON DELETE RESTRICT,
    vendor_type          text        NOT NULL CHECK (vendor_type IN ('INDIVIDUAL', 'BUSINESS')),
    display_name         varchar(150) NOT NULL,
    business_name        varchar(200),
    gstin                varchar(15) UNIQUE,
    pan_last4            char(4),
    support_phone        varchar(20),
    support_email        citext,
    verification_status  text        NOT NULL DEFAULT 'PENDING'
                         CHECK (verification_status IN ('PENDING', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'SUSPENDED')),
    rejection_reason     varchar(500),
    verified_by          uuid REFERENCES users (id),
    verified_at          timestamptz,
    rating_avg           numeric(3, 2) NOT NULL DEFAULT 0,
    rating_count         integer     NOT NULL DEFAULT 0,
    version              bigint      NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_vendor_business_name
        CHECK (vendor_type = 'INDIVIDUAL' OR business_name IS NOT NULL)
);
CREATE INDEX idx_vendor_profiles_status ON vendor_profiles (verification_status);
CREATE TRIGGER trg_vendor_profiles_updated BEFORE UPDATE ON vendor_profiles
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Documents live in a private S3 bucket; only the key is stored here.
CREATE TABLE vendor_documents (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    vendor_id     uuid         NOT NULL REFERENCES vendor_profiles (id) ON DELETE CASCADE,
    doc_type      text         NOT NULL
                  CHECK (doc_type IN ('PAN', 'AADHAAR', 'GST_CERTIFICATE', 'BUSINESS_REGISTRATION',
                                      'ADDRESS_PROOF', 'CANCELLED_CHEQUE', 'OTHER')),
    s3_key        varchar(500) NOT NULL,
    mime_type     varchar(100) NOT NULL,
    size_bytes    bigint       NOT NULL CHECK (size_bytes > 0),
    status        text         NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reviewed_by   uuid REFERENCES users (id),
    reviewed_at   timestamptz,
    review_note   varchar(500),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_vendor_documents_vendor ON vendor_documents (vendor_id);
CREATE TRIGGER trg_vendor_documents_updated BEFORE UPDATE ON vendor_documents
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE vendor_bank_accounts (
    id                         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    vendor_id                  uuid         NOT NULL REFERENCES vendor_profiles (id) ON DELETE CASCADE,
    account_holder_name        varchar(150) NOT NULL,
    account_last4              char(4)      NOT NULL,
    ifsc                       char(11)     NOT NULL,
    bank_name                  varchar(120),
    gateway                    varchar(30),
    gateway_linked_account_id  varchar(100) UNIQUE,
    status                     text         NOT NULL DEFAULT 'PENDING'
                               CHECK (status IN ('PENDING', 'VERIFIED', 'FAILED', 'INACTIVE')),
    is_primary                 boolean      NOT NULL DEFAULT false,
    created_at                 timestamptz  NOT NULL DEFAULT now(),
    updated_at                 timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_vendor_bank_accounts_vendor ON vendor_bank_accounts (vendor_id);
CREATE UNIQUE INDEX uq_vendor_bank_accounts_primary ON vendor_bank_accounts (vendor_id) WHERE is_primary;
CREATE TRIGGER trg_vendor_bank_accounts_updated BEFORE UPDATE ON vendor_bank_accounts
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
