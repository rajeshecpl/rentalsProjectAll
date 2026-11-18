-- =====================================================================
-- V7: reviews, wishlists, notifications, support tickets and audit logs.
-- =====================================================================

-- One review per booking, only by the customer who booked (enforced in service).
CREATE TABLE reviews (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id         uuid        NOT NULL UNIQUE REFERENCES bookings (id) ON DELETE CASCADE,
    listing_id         uuid        NOT NULL REFERENCES listings (id) ON DELETE CASCADE,
    customer_id        uuid        NOT NULL REFERENCES users (id),
    vendor_id          uuid        NOT NULL REFERENCES vendor_profiles (id),
    rating             smallint    NOT NULL CHECK (rating BETWEEN 1 AND 5),
    title              varchar(150),
    body               text,
    vendor_reply       text,
    vendor_replied_at  timestamptz,
    moderation_status  text        NOT NULL DEFAULT 'PENDING'
                       CHECK (moderation_status IN ('PENDING', 'APPROVED', 'REJECTED', 'HIDDEN')),
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_reviews_listing ON reviews (listing_id, created_at DESC) WHERE moderation_status = 'APPROVED';
CREATE INDEX idx_reviews_vendor ON reviews (vendor_id);
CREATE TRIGGER trg_reviews_updated BEFORE UPDATE ON reviews
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE wishlists (
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    listing_id  uuid        NOT NULL REFERENCES listings (id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, listing_id)
);
CREATE INDEX idx_wishlists_listing ON wishlists (listing_id);

CREATE TABLE notifications (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    channel       text         NOT NULL CHECK (channel IN ('IN_APP', 'EMAIL', 'SMS', 'PUSH')),
    template_code varchar(80)  NOT NULL,
    title         varchar(200),
    body          text,
    data          jsonb        NOT NULL DEFAULT '{}'::jsonb,
    status        text         NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'READ')),
    attempts      smallint     NOT NULL DEFAULT 0,
    sent_at       timestamptz,
    read_at       timestamptz,
    created_at    timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_user ON notifications (user_id, created_at DESC);
CREATE INDEX idx_notifications_pending ON notifications (created_at) WHERE status = 'PENDING';

CREATE SEQUENCE ticket_number_seq START 10001;

CREATE TABLE support_tickets (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_number  varchar(20)  NOT NULL UNIQUE DEFAULT ('TKT' || nextval('ticket_number_seq')),
    raised_by      uuid         NOT NULL REFERENCES users (id),
    booking_id     uuid REFERENCES bookings (id),
    listing_id     uuid REFERENCES listings (id),
    category       text         NOT NULL
                   CHECK (category IN ('BOOKING', 'PAYMENT', 'REFUND', 'LISTING_REPORT', 'ACCOUNT',
                                       'VENDOR_DISPUTE', 'OTHER')),
    subject        varchar(200) NOT NULL,
    priority       text         NOT NULL DEFAULT 'NORMAL'
                   CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    status         text         NOT NULL DEFAULT 'OPEN'
                   CHECK (status IN ('OPEN', 'IN_PROGRESS', 'WAITING_ON_CUSTOMER', 'ESCALATED', 'RESOLVED', 'CLOSED')),
    assigned_to    uuid REFERENCES users (id),
    resolved_at    timestamptz,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_support_tickets_raised_by ON support_tickets (raised_by, created_at DESC);
CREATE INDEX idx_support_tickets_queue ON support_tickets (status, priority, created_at)
    WHERE status NOT IN ('RESOLVED', 'CLOSED');
CREATE TRIGGER trg_support_tickets_updated BEFORE UPDATE ON support_tickets
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE ticket_messages (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id        uuid        NOT NULL REFERENCES support_tickets (id) ON DELETE CASCADE,
    author_id        uuid        NOT NULL REFERENCES users (id),
    body             text        NOT NULL,
    is_internal      boolean     NOT NULL DEFAULT false,  -- staff-only note
    attachment_s3_key varchar(500),
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_ticket_messages_ticket ON ticket_messages (ticket_id, created_at);

-- Append-only record of sensitive actions (admin, verification, money).
CREATE TABLE audit_logs (
    id            bigserial PRIMARY KEY,
    actor_id      uuid REFERENCES users (id),
    actor_role    varchar(30),
    action        varchar(80)  NOT NULL,
    entity_type   varchar(60)  NOT NULL,
    entity_id     varchar(64),
    before_state  jsonb,
    after_state   jsonb,
    ip_address    varchar(45),
    user_agent    varchar(300),
    created_at    timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_logs_entity ON audit_logs (entity_type, entity_id, created_at DESC);
CREATE INDEX idx_audit_logs_actor ON audit_logs (actor_id, created_at DESC);
