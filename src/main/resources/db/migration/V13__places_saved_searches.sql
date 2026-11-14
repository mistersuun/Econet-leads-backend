-- V13: Google Places enrichment and saved discovery searches.

-- 1. Google data on a lead (see PlacesService for the JSON shape). places_checked_at is set on every
--    lookup, including those that found no confident match.
ALTER TABLE businesses ADD COLUMN places JSONB;
ALTER TABLE businesses ADD COLUMN places_checked_at TIMESTAMP;
-- Denormalised "has cleanliness review quotes" flag so the call queue can add its priority bonus in SQL.
ALTER TABLE businesses ADD COLUMN places_cleanliness BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN businesses.places IS 'Google Places data: placeId, rating, reviewCount, mapsUrl, website, phone, openingHours, businessStatus, cleanlinessSignals, fetchedAt. Expires after 30 days (only placeId and fetchedAt are kept)';

-- 2. Places API calls per day (daily budget, PLACES_DAILY_LIMIT)
CREATE TABLE places_usage (
    day   DATE PRIMARY KEY,
    calls INT  NOT NULL DEFAULT 0
);

-- 3. Saved discovery searches, re-run by a daily scheduler
CREATE TABLE saved_searches (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name              VARCHAR(150) NOT NULL,
    city              VARCHAR(100) NOT NULL,
    categories        JSONB        NOT NULL,
    max_results       INT          NOT NULL DEFAULT 100,
    frequency         VARCHAR(20)  NOT NULL DEFAULT 'monthly',
    assign_to_user_id UUID         REFERENCES users(id) ON DELETE SET NULL,
    active            BOOLEAN      NOT NULL DEFAULT TRUE,
    last_run_at       TIMESTAMP,
    last_added        INT,
    created_by        UUID         REFERENCES users(id) ON DELETE SET NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_saved_searches_frequency CHECK (frequency IN ('monthly', 'weekly'))
);
