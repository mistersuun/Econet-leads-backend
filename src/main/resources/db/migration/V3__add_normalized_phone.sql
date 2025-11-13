-- Add normalized_phone column for duplicate detection
-- This stores digits-only phone for comparison while keeping formatted phone for display

ALTER TABLE businesses ADD COLUMN phone_normalized VARCHAR(15);

-- Create index on normalized phone for fast duplicate detection
CREATE INDEX idx_businesses_phone_normalized ON businesses(phone_normalized);

-- Populate normalized phone from existing phone data (remove all non-digits)
UPDATE businesses
SET phone_normalized = REGEXP_REPLACE(phone, '[^0-9]', '', 'g')
WHERE phone IS NOT NULL;

-- Add comment explaining the column
COMMENT ON COLUMN businesses.phone_normalized IS 'Normalized phone (digits only) for duplicate detection. phone column contains formatted display version.';
