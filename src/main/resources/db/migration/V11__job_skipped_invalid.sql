-- File imports report rows skipped as duplicates and rows rejected as invalid; NULL for other jobs.
ALTER TABLE scraper_jobs ADD COLUMN records_skipped INT;
ALTER TABLE scraper_jobs ADD COLUMN records_invalid INT;
