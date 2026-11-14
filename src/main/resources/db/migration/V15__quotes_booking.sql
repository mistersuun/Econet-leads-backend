-- P5: quotes with a public accept link, self-serve visit booking.

-- 1. Quotes (PDF + accept link). input = the estimate input, lines = itemised lines as rendered.
CREATE TABLE quotes (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id      UUID          NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    number           VARCHAR(20)   NOT NULL UNIQUE,
    language         VARCHAR(2)    NOT NULL,
    input            JSONB         NOT NULL,
    lines            JSONB         NOT NULL,
    subtotal         NUMERIC(10,2) NOT NULL,
    tax              NUMERIC(10,2) NOT NULL,
    total            NUMERIC(10,2) NOT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'draft',
    token            VARCHAR(64)   NOT NULL UNIQUE,
    valid_until      DATE          NOT NULL,
    created_by       UUID          REFERENCES users(id) ON DELETE SET NULL,
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at          TIMESTAMP,
    accepted_at      TIMESTAMP,
    accepted_name    VARCHAR(200),
    accepted_ip_hash VARCHAR(64),
    declined_at      TIMESTAMP,
    decline_reason   VARCHAR(500),
    CONSTRAINT ck_quotes_status CHECK (status IN ('draft', 'sent', 'accepted', 'declined', 'expired')),
    CONSTRAINT ck_quotes_language CHECK (language IN ('fr', 'en'))
);
CREATE INDEX idx_quotes_business ON quotes(business_id, created_at DESC);
CREATE INDEX idx_quotes_creator_accepted ON quotes(created_by, accepted_at);
COMMENT ON COLUMN quotes.number IS 'Q-YYYY-NNNN';
COMMENT ON COLUMN quotes.token IS '32 random bytes, base64url: the secret of the public link';
COMMENT ON COLUMN quotes.accepted_ip_hash IS 'SHA-256 of the accepting IP (never the IP itself)';

-- 2. Weekly availability for visits (weekday 1 = Monday ... 7 = Sunday, America/Montreal wall-clock)
CREATE TABLE availability (
    id           UUID     PRIMARY KEY DEFAULT gen_random_uuid(),
    weekday      INT      NOT NULL,
    start_time   TIME     NOT NULL,
    end_time     TIME     NOT NULL,
    slot_minutes INT      NOT NULL DEFAULT 60,
    active       BOOLEAN  NOT NULL DEFAULT true,
    CONSTRAINT ck_availability_weekday CHECK (weekday BETWEEN 1 AND 7),
    CONSTRAINT ck_availability_range CHECK (end_time > start_time)
);
INSERT INTO availability (id, weekday, start_time, end_time, slot_minutes, active) VALUES
    (gen_random_uuid(), 1, '09:00', '17:00', 60, true),
    (gen_random_uuid(), 2, '09:00', '17:00', 60, true),
    (gen_random_uuid(), 3, '09:00', '17:00', 60, true),
    (gen_random_uuid(), 4, '09:00', '17:00', 60, true),
    (gen_random_uuid(), 5, '09:00', '17:00', 60, true);

-- 3. Visits (booked by the prospect through a link, or manually)
CREATE TABLE visits (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id UUID        NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    starts_at   TIMESTAMP   NOT NULL,
    ends_at     TIMESTAMP   NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'booked',
    booked_via  VARCHAR(10) NOT NULL DEFAULT 'link',
    created_by  UUID        REFERENCES users(id) ON DELETE SET NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_visits_status CHECK (status IN ('booked', 'done', 'cancelled')),
    CONSTRAINT ck_visits_via CHECK (booked_via IN ('link', 'manual')),
    CONSTRAINT ck_visits_range CHECK (ends_at > starts_at)
);
CREATE INDEX idx_visits_starts ON visits(starts_at);
CREATE INDEX idx_visits_business ON visits(business_id);

-- 4. Booking links (single use, 14 days); visit_id is set once the prospect has booked
CREATE TABLE booking_links (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    token       VARCHAR(64) NOT NULL UNIQUE,
    business_id UUID        NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    created_by  UUID        REFERENCES users(id) ON DELETE SET NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at  TIMESTAMP   NOT NULL,
    used_at     TIMESTAMP,
    visit_id    UUID        REFERENCES visits(id) ON DELETE SET NULL
);
CREATE INDEX idx_booking_links_business ON booking_links(business_id, created_at DESC);
CREATE INDEX idx_booking_links_creator_used ON booking_links(created_by, used_at);
