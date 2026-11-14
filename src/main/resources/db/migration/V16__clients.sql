-- V16: clients after the sale. A business that moves to WON becomes a client (one row per business)
-- with the recurring service, its price, the renewal date and the follow-up facts (first cleaning
-- done, Google review requested). Drives the Clients page, the MRR tile and three notifications.
CREATE TABLE clients (
    id                      UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id             UUID           NOT NULL UNIQUE REFERENCES businesses(id) ON DELETE CASCADE,
    service                 VARCHAR(200)   NOT NULL,
    frequency               VARCHAR(20)    NOT NULL DEFAULT 'one-time',
    price_per_visit         NUMERIC(10, 2) NOT NULL DEFAULT 0,
    start_date              DATE           NOT NULL DEFAULT CURRENT_DATE,
    contract_end_date       DATE,
    next_visit_at           TIMESTAMP,
    status                  VARCHAR(20)    NOT NULL DEFAULT 'active',
    first_cleaning_done_at  TIMESTAMP,
    review_requested_at     TIMESTAMP,
    notes                   TEXT,
    created_at              TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_clients_frequency CHECK (frequency IN ('weekly', 'bi-weekly', 'monthly', 'one-time')),
    CONSTRAINT ck_clients_status CHECK (status IN ('active', 'paused', 'ended'))
);
CREATE INDEX idx_clients_status ON clients(status);
CREATE INDEX idx_clients_contract_end ON clients(contract_end_date) WHERE status = 'active';
CREATE TRIGGER update_clients_updated_at BEFORE UPDATE ON clients
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Leads already WON before this migration become clients (price from the estimated value when known).
INSERT INTO clients (business_id, service, frequency, price_per_visit, start_date)
SELECT b.id,
       'Entretien ménager',
       CASE WHEN b.qualification ->> 'frequencyWanted' IN ('weekly', 'bi-weekly', 'monthly', 'one-time')
            THEN b.qualification ->> 'frequencyWanted' ELSE 'one-time' END,
       COALESCE(b.estimated_value, 0),
       CAST(COALESCE(b.updated_at, CURRENT_TIMESTAMP) AS DATE)
FROM businesses b
WHERE b.lead_status = 'WON';
