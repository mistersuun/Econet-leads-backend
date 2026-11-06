-- Addendum 4: adding leads (manual, file import, OpenStreetMap discovery, website requests).
-- New source types are stored as plain strings (source_type VARCHAR(50)); no schema change is needed.
-- All rows are inactive: none of them is part of the scheduled "import all" run. The website source
-- is activated at startup (once) when WEBSITE_DB_URL is configured; see WebsiteRequestsService.

INSERT INTO data_sources (id, source_name, source_type, source_url, sync_frequency, active, config, created_at)
VALUES (
    gen_random_uuid(),
    'Site web – demandes',
    'WEBSITE_DB',
    'https://econet-proprete.ca',
    'MANUAL',
    false,
    '{"importer": "WEBSITE_REQUESTS", "tables": ["booking_requests", "contact_submissions"], "lookbackDays": 1}',
    CURRENT_TIMESTAMP
);

-- Leads typed in by hand (POST /api/businesses)
INSERT INTO data_sources (id, source_name, source_type, source_url, sync_frequency, active, config, created_at)
VALUES (
    gen_random_uuid(),
    'Manuel',
    'MANUAL',
    NULL,
    'MANUAL',
    false,
    '{}',
    CURRENT_TIMESTAMP
);

-- Leads found on the map (POST /api/discover/osm/import); data © OpenStreetMap contributors (ODbL)
INSERT INTO data_sources (id, source_name, source_type, source_url, sync_frequency, active, config, created_at)
VALUES (
    gen_random_uuid(),
    'OpenStreetMap',
    'OPENSTREETMAP',
    'https://www.openstreetmap.org/copyright',
    'MANUAL',
    false,
    '{"attribution": "© OpenStreetMap contributors"}',
    CURRENT_TIMESTAMP
);
-- "Import – <name>" rows (source_type MANUAL_FILE) are created on demand by POST /api/imports/{id}/run.
