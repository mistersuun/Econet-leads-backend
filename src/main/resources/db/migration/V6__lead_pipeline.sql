-- V6: Calling CRM / lead pipeline fields on businesses

ALTER TABLE businesses
    ADD COLUMN lead_status       VARCHAR(30)   NOT NULL DEFAULT 'NEW',
    ADD COLUMN assigned_to       UUID          NULL REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN last_contacted_at TIMESTAMP,
    ADD COLUMN next_follow_up_at TIMESTAMP,
    ADD COLUMN contact_count     INT           NOT NULL DEFAULT 0,
    ADD COLUMN estimated_value   DECIMAL(10, 2);

ALTER TABLE businesses
    ADD CONSTRAINT ck_businesses_lead_status CHECK (lead_status IN
        ('NEW', 'CONTACTED', 'INTERESTED', 'QUOTE_SENT', 'WON', 'LOST', 'DO_NOT_CALL'));

COMMENT ON COLUMN businesses.lead_status IS 'Sales pipeline status (LeadStatus enum)';
COMMENT ON COLUMN businesses.assigned_to IS 'User who owns this lead (set to the first caller when unassigned)';
COMMENT ON COLUMN businesses.contact_count IS 'Number of logged contacts (denormalized for queue/list sorting)';
COMMENT ON COLUMN businesses.estimated_value IS 'Estimated contract value in CAD';

-- Backfill from existing contact history
UPDATE businesses b
SET contact_count     = c.cnt,
    last_contacted_at = c.last_date,
    lead_status       = CASE WHEN c.has_contract THEN 'WON' ELSE 'CONTACTED' END
FROM (
    SELECT business_id,
           COUNT(*)                      AS cnt,
           MAX(contact_date)             AS last_date,
           BOOL_OR(outcome = 'CONTRAT')  AS has_contract
    FROM contacts
    GROUP BY business_id
) c
WHERE c.business_id = b.id;

-- Earliest pending follow-up (from the legacy contacts.next_action_date) becomes next_follow_up_at
UPDATE businesses b
SET next_follow_up_at = f.next_date
FROM (
    SELECT business_id, MIN(next_action_date) AS next_date
    FROM contacts
    WHERE next_action_date IS NOT NULL AND next_action_date >= CURRENT_DATE
    GROUP BY business_id
) f
WHERE f.business_id = b.id AND b.lead_status <> 'WON';

-- Indexes for list filters / dashboard aggregates
CREATE INDEX idx_businesses_lead_status ON businesses(lead_status);
CREATE INDEX idx_businesses_next_follow_up_at ON businesses(next_follow_up_at);
CREATE INDEX idx_businesses_assigned_to ON businesses(assigned_to);
CREATE INDEX idx_businesses_last_contacted_at ON businesses(last_contacted_at);

-- Call queue, part 1: follow-ups due on open leads, oldest first
CREATE INDEX idx_businesses_queue_follow_up ON businesses(next_follow_up_at)
    WHERE next_follow_up_at IS NOT NULL
      AND lead_status NOT IN ('WON', 'LOST', 'DO_NOT_CALL');

-- Call queue, part 2: NEW leads with a phone, best quality first then oldest
CREATE INDEX idx_businesses_queue_new ON businesses(data_quality_score DESC NULLS LAST, created_at ASC)
    WHERE lead_status = 'NEW' AND phone IS NOT NULL;

-- Dashboard: calls per day / outcome / user
CREATE INDEX idx_contacts_type_date ON contacts(contact_type, contact_date);
CREATE INDEX idx_contacts_outcome ON contacts(outcome);

-- contacts.outcome (VARCHAR(50)) now holds the CallOutcome values
-- (NO_ANSWER, VOICEMAIL, CALLBACK, INTERESTED, NOT_INTERESTED, QUOTE_SENT, WON, WRONG_NUMBER, DO_NOT_CALL);
-- legacy values CONTRAT, REFUS, EN_ATTENTE are kept as-is and remain readable.
-- contacts.contact_type (VARCHAR(20)) gains the value NOTE for pipeline status changes.
COMMENT ON COLUMN contacts.outcome IS 'CallOutcome value, or legacy CONTRAT/REFUS/EN_ATTENTE';
