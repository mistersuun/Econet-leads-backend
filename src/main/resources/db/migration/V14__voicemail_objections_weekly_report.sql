-- V14: faster calls — objections logged on calls, a voicemail section in every call script,
-- and the opt-out flag of the Monday coaching email.

-- 1. Objections heard during a call (JSON array of short strings, at most 5)
ALTER TABLE contacts ADD COLUMN objections JSONB;
COMMENT ON COLUMN contacts.objections IS 'Objections heard on the call: JSON array of at most 5 short strings';

-- 2. Weekly coaching email (Monday morning); on by default, each user can turn it off
ALTER TABLE users ADD COLUMN weekly_report BOOLEAN NOT NULL DEFAULT TRUE;

-- 3. Voicemail section appended to every script that does not have one yet (seeded or admin-edited)
UPDATE call_scripts
SET sections = sections || jsonb_build_array(jsonb_build_object(
        'key', 'voicemail',
        'title', CASE WHEN language = 'en' THEN 'Voicemail' ELSE 'Message vocal' END,
        'body', CASE WHEN language = 'en'
            THEN 'Hi, this is {{callerName}} from {{companyName}}. I''m calling about commercial cleaning for {{businessName}}. We help businesses like yours keep their premises spotless with no management hassle. I''ll try you again, or you can reach me back at this number. Thank you, have a great day!'
            ELSE 'Bonjour, ici {{callerName}} de {{companyName}}. Je vous appelle au sujet de l''entretien ménager commercial pour {{businessName}}. On aide des entreprises comme la vôtre à avoir des locaux impeccables sans casse-tête de gestion. Je vais vous rappeler, ou vous pouvez me joindre à ce numéro. Merci et bonne journée!'
        END)),
    updated_at = CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM jsonb_array_elements(call_scripts.sections) AS s(section) WHERE s.section ->> 'key' = 'voicemail'
);
