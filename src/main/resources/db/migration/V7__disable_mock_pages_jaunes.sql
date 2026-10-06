-- "Pages Jaunes - Manual Scraping" is a mock: it inserts 10 invented businesses with
-- 555 phone numbers. Keep it off so nobody imports fake leads into the call queue.
-- (Fake rows imported earlier can be reviewed with:
--   SELECT * FROM businesses WHERE data_source = 'Pages Jaunes - Manual Scraping';)
UPDATE data_sources SET active = false WHERE source_name = 'Pages Jaunes - Manual Scraping';
