-- Increase phone field length to accommodate longer phone numbers
ALTER TABLE businesses ALTER COLUMN phone TYPE VARCHAR(50);
