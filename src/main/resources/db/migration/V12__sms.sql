-- V12: SMS log (owner alerts and follow-up texts to prospects). Phone numbers are stored masked
-- (last 4 digits only); follow-up bodies are kept up to 300 characters, alert bodies not at all.
CREATE TABLE sms_messages (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id  UUID         REFERENCES businesses(id) ON DELETE SET NULL,
    user_id      UUID         REFERENCES users(id) ON DELETE SET NULL,
    to_masked    VARCHAR(30)  NOT NULL,
    kind         VARCHAR(20)  NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    provider_sid VARCHAR(64),
    error        VARCHAR(500),
    body         VARCHAR(300),
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_sms_messages_kind CHECK (kind IN ('alert', 'follow_up')),
    CONSTRAINT ck_sms_messages_status CHECK (status IN ('sent', 'failed'))
);
CREATE INDEX idx_sms_messages_business ON sms_messages(business_id, kind);
CREATE INDEX idx_sms_messages_kind_date ON sms_messages(kind, created_at);
