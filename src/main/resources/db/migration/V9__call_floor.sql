-- V9: "the living call floor" — call durations, structured qualification, call scripts, email
-- templates, daily targets. Seeds the FR/EN scripts (default + CPE, Clinique, Restaurant, Bureau,
-- Chantier) and the FR/EN email templates (quote_followup, visit_confirmation, missed_you, intro).
-- Placeholders ({{businessName}}, {{city}}, {{callerName}}, {{companyName}}, {{decisionMaker}}, ...)
-- are substituted client-side for scripts and server-side for emails.

-- 1. Calls carry a duration
ALTER TABLE contacts ADD COLUMN duration_seconds INT;
COMMENT ON COLUMN contacts.duration_seconds IS 'Duration of the call in seconds (APPEL contacts), when the caller timed it';

-- 2. Structured facts captured during calls (Qualification JSON, see BusinessDTO.qualification)
ALTER TABLE businesses ADD COLUMN qualification JSONB;
COMMENT ON COLUMN businesses.qualification IS 'Qualification JSON: decisionMaker, role, directPhone, email, currentProvider, contractEndsAt, sizeSqft, employees, frequencyWanted, painPoints, language, bestTimeToCall, notes';

-- 3. Daily targets per user (scoreboard)
ALTER TABLE users
    ADD COLUMN daily_call_target         INT NOT NULL DEFAULT 40,
    ADD COLUMN daily_conversation_target INT NOT NULL DEFAULT 6;

-- 4. Call scripts
CREATE TABLE call_scripts (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(200) NOT NULL,
    business_type VARCHAR(100),
    language      VARCHAR(2)   NOT NULL,
    sections      JSONB        NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT true,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_call_scripts_language CHECK (language IN ('fr', 'en'))
);
CREATE INDEX idx_call_scripts_lookup ON call_scripts(language, business_type) WHERE active;
CREATE TRIGGER update_call_scripts_updated_at BEFORE UPDATE ON call_scripts
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
COMMENT ON COLUMN call_scripts.business_type IS 'NULL = default script for the language';
COMMENT ON COLUMN call_scripts.sections IS '[{"key": "opening|questions|objections|closing", "title": "...", "body": "..."}]';

-- 5. Email templates
CREATE TABLE email_templates (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    template_key VARCHAR(50) NOT NULL,
    language   VARCHAR(2)   NOT NULL,
    subject    VARCHAR(500) NOT NULL,
    body       TEXT         NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT true,
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_email_templates_key_language UNIQUE (template_key, language),
    CONSTRAINT ck_email_templates_key CHECK (template_key IN ('quote_followup', 'visit_confirmation', 'missed_you', 'intro')),
    CONSTRAINT ck_email_templates_language CHECK (language IN ('fr', 'en'))
);
CREATE TRIGGER update_email_templates_updated_at BEFORE UPDATE ON email_templates
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- 6. Indexes for the scoreboard (calls per user per day, streaks) and the per-lead history
CREATE INDEX idx_contacts_user_date ON contacts(user_id, contact_date);
CREATE INDEX idx_contacts_business_date ON contacts(business_id, contact_date DESC);

-- 7. Seed: call scripts
INSERT INTO call_scripts (id, name, business_type, language, sections, active, updated_at) VALUES (
    gen_random_uuid(), 'Script général', NULL, 'fr',
    '[{"key": "opening", "title": "Ouverture", "body": "Bonjour, {{decisionMaker}}? Ici {{callerName}} de {{companyName}}, une entreprise d''entretien ménager commercial de la région de {{city}}. Je vous appelle parce qu''on travaille avec plusieurs entreprises comme {{businessName}} et on les aide à avoir des locaux impeccables sans que ça leur demande du temps de gestion. Est-ce que je vous prends à un bon moment, deux minutes?"}, {"key": "questions", "title": "Questions de qualification", "body": "1. Présentement, qui s''occupe de l''entretien chez vous : une compagnie externe ou quelqu''un à l''interne?\n2. Jusqu''à quand votre entente actuelle vous engage-t-elle?\n3. À quelle fréquence le ménage est-il fait : chaque jour, chaque semaine, aux deux semaines?\n4. Environ quelle superficie avez-vous, ou combien d''employés travaillent sur place?\n5. Qu''est-ce qui vous agace le plus avec votre service actuel : la qualité, la fiabilité, le prix, les produits utilisés?\n6. Si vous deviez changer, qu''est-ce qui serait le plus important pour vous?"}, {"key": "objections", "title": "Objections et réponses", "body": "« On a déjà quelqu''un. »\n→ Parfait, c''est normal : la plupart de nos clients avaient déjà un fournisseur. Je ne vous demande pas de changer aujourd''hui, seulement de savoir ce qu''on vous offrirait pour comparer quand votre entente viendra à échéance. C''est quand, à peu près?\n\n« On n''a pas de budget pour ça. »\n→ Je comprends. Souvent on arrive au même budget que vous avez déjà, parfois en dessous, parce qu''on ajuste la fréquence et les tâches à vos vrais besoins. Une visite de vingt minutes nous permettrait de vous donner un chiffre précis, sans engagement.\n\n« Envoyez-moi un courriel. »\n→ Avec plaisir. Pour que ce soit utile et pas un courriel générique, je peux vous poser deux petites questions? À quelle fréquence le ménage est fait, et environ quelle superficie? Je vous l''envoie aujourd''hui, à quelle adresse?\n\n« Je n''ai pas le temps. »\n→ Je comprends, je serai bref. Est-ce que mardi ou jeudi en avant-midi vous conviendrait mieux pour cinq minutes?\n\n« Rappelez-moi plus tard. »\n→ Certainement. Pour ne pas vous déranger au mauvais moment, quel jour et quelle heure sont les meilleurs pour vous? Je le note et je vous rappelle exactement à ce moment-là.\n\n« C''est le siège social qui décide. »\n→ D''accord. Qui serait la bonne personne là-bas, et puis-je mentionner que je vous ai parlé? De votre côté, est-ce que la qualité du service actuel vous convient? Votre avis compte quand le siège social révise les contrats.\n\n« On est satisfaits de notre fournisseur. »\n→ Tant mieux, c''est rare! Dans ce cas, gardez simplement nos coordonnées : si un jour la qualité baisse ou que les prix montent, vous aurez une option déjà évaluée. Puis-je vous envoyer un court résumé de nos services?\n\n« C''est trop cher. »\n→ Comparé à quoi? Si vous me dites ce que vous payez et ce qui est inclus, je vous dis franchement si on peut faire mieux. Et si on ne peut pas, je vous le dirai aussi."}, {"key": "closing", "title": "Conclusion : proposer la visite", "body": "Ce que je vous propose est simple : on passe faire une visite gratuite de vos locaux, vingt minutes, et vous recevez une soumission détaillée dans les 48 heures, sans aucun engagement. Est-ce que mardi en avant-midi ou jeudi en après-midi vous conviendrait?\n[Confirmer l''adresse, le nom de la personne et le courriel.]\nMerci {{decisionMaker}}, à bientôt."}]'::jsonb,
    true, CURRENT_TIMESTAMP);

INSERT INTO call_scripts (id, name, business_type, language, sections, active, updated_at) VALUES (
    gen_random_uuid(), 'General script', NULL, 'en',
    '[{"key": "opening", "title": "Opening", "body": "Hello, {{decisionMaker}}? This is {{callerName}} from {{companyName}}, a commercial cleaning company in the {{city}} area. I''m calling because we work with several businesses like {{businessName}} and help them keep spotless premises without it taking up any of their management time. Is this a good time, two minutes?"}, {"key": "questions", "title": "Qualifying questions", "body": "1. Right now, who takes care of the cleaning: an outside company or someone in-house?\n2. Until when does your current agreement run?\n3. How often is the cleaning done: daily, weekly, every two weeks?\n4. Roughly what size is the space, or how many employees work on site?\n5. What bothers you most about your current service: quality, reliability, price, the products used?\n6. If you were to switch, what would matter most to you?"}, {"key": "objections", "title": "Objections and replies", "body": "« We already have someone. »\n→ Perfect, that''s normal: most of our clients already had a provider. I''m not asking you to switch today, only to let you see what we would offer so you can compare when your agreement comes up for renewal. Roughly when is that?\n\n« We don''t have a budget for this. »\n→ I understand. We often land at the budget you already have, sometimes below it, because we adjust the frequency and the tasks to what you actually need. A twenty-minute visit would let us give you an exact number, no commitment.\n\n« Send me an email. »\n→ Gladly. So it''s useful and not a generic email, may I ask you two quick questions? How often is the cleaning done, and roughly what size is the space? I''ll send it today. Which address should I use?\n\n« I don''t have time. »\n→ I understand, I''ll be brief. Would Tuesday or Thursday morning work better for five minutes?\n\n« Call me back later. »\n→ Of course. So I don''t catch you at a bad time, which day and time work best? I''ll note it and call you back exactly then.\n\n« Head office decides that. »\n→ Understood. Who would be the right person there, and may I mention we spoke? On your side, are you happy with the current service? Your opinion matters when head office reviews contracts.\n\n« We''re happy with our provider. »\n→ Good to hear, that''s rare! In that case, simply keep our contact details: if quality ever drops or prices go up, you''ll have an option already evaluated. May I send you a short summary of our services?\n\n« It''s too expensive. »\n→ Compared to what? If you tell me what you pay and what''s included, I''ll tell you honestly whether we can do better. And if we can''t, I''ll tell you that too."}, {"key": "closing", "title": "Close: propose the site visit", "body": "Here''s what I suggest, and it''s simple: we come by for a free twenty-minute visit of your premises, and you receive a detailed quote within 48 hours, with no commitment whatsoever. Would Tuesday morning or Thursday afternoon work for you?\n[Confirm the address, the contact name and the email.]\nThank you {{decisionMaker}}, talk soon."}]'::jsonb,
    true, CURRENT_TIMESTAMP);

INSERT INTO call_scripts (id, name, business_type, language, sections, active, updated_at) VALUES (
    gen_random_uuid(), 'Script CPE et garderies', 'CPE', 'fr',
    '[{"key": "opening", "title": "Ouverture", "body": "Bonjour, {{decisionMaker}}? Ici {{callerName}} de {{companyName}}. On fait l''entretien ménager de plusieurs CPE et garderies de {{city}}, avec des produits sans danger pour les enfants et une désinfection adaptée aux exigences du ministère. Je vous appelle pour voir comment ça se passe de votre côté à {{businessName}}. Vous avez deux minutes?"}, {"key": "questions", "title": "Questions de qualification", "body": "1. Qui fait le ménage en ce moment : une compagnie ou votre personnel éducateur en fin de journée?\n2. Le ménage est fait tous les soirs? Et la désinfection des jouets et des surfaces, à quelle fréquence?\n3. Combien d''installations et de locaux avez-vous, environ?\n4. Est-ce que les produits utilisés sont sans parfum et sans danger pour les enfants? Vos parents vous en parlent?\n5. Jusqu''à quand votre entente actuelle vous engage-t-elle, et qui la révise : vous ou le conseil d''administration?\n6. Qu''est-ce qui vous ferait dire que le service est vraiment bon : la constance, la désinfection, les horaires?"}, {"key": "objections", "title": "Objections et réponses", "body": "« Le ménage est fait par nos éducatrices. »\n→ C''est fréquent, mais ça leur enlève du temps avec les enfants et la désinfection est rarement complète. On pourrait prendre le soir et la désinfection, et elles garderaient seulement le rangement de la journée.\n\n« On a déjà quelqu''un. »\n→ Parfait, c''est normal : la plupart de nos clients avaient déjà un fournisseur. Je ne vous demande pas de changer aujourd''hui, seulement de savoir ce qu''on vous offrirait pour comparer quand votre entente viendra à échéance. C''est quand, à peu près?\n\n« On n''a pas de budget pour ça. »\n→ Je comprends. Souvent on arrive au même budget que vous avez déjà, parfois en dessous, parce qu''on ajuste la fréquence et les tâches à vos vrais besoins. Une visite de vingt minutes nous permettrait de vous donner un chiffre précis, sans engagement.\n\n« Envoyez-moi un courriel. »\n→ Avec plaisir. Pour que ce soit utile et pas un courriel générique, je peux vous poser deux petites questions? À quelle fréquence le ménage est fait, et environ quelle superficie? Je vous l''envoie aujourd''hui, à quelle adresse?\n\n« Je n''ai pas le temps. »\n→ Je comprends, je serai bref. Est-ce que mardi ou jeudi en avant-midi vous conviendrait mieux pour cinq minutes?\n\n« Rappelez-moi plus tard. »\n→ Certainement. Pour ne pas vous déranger au mauvais moment, quel jour et quelle heure sont les meilleurs pour vous? Je le note et je vous rappelle exactement à ce moment-là.\n\n« C''est le siège social qui décide. »\n→ D''accord. Qui serait la bonne personne là-bas, et puis-je mentionner que je vous ai parlé? De votre côté, est-ce que la qualité du service actuel vous convient? Votre avis compte quand le siège social révise les contrats.\n\n« C''est le conseil d''administration qui décide. »\n→ Bien sûr. Je peux vous préparer une soumission claire que vous pourrez présenter au CA. Vous connaissez la date de leur prochaine rencontre?"}, {"key": "closing", "title": "Conclusion : proposer la visite", "body": "Ce que je vous propose est simple : on passe faire une visite gratuite de vos locaux, vingt minutes, et vous recevez une soumission détaillée dans les 48 heures, sans aucun engagement. Est-ce que mardi en avant-midi ou jeudi en après-midi vous conviendrait?\n[Confirmer l''adresse, le nom de la personne et le courriel.]\nMerci {{decisionMaker}}, à bientôt."}]'::jsonb,
    true, CURRENT_TIMESTAMP);

INSERT INTO call_scripts (id, name, business_type, language, sections, active, updated_at) VALUES (
    gen_random_uuid(), 'Daycare and early-childhood centre script', 'CPE', 'en',
    '[{"key": "opening", "title": "Opening", "body": "Hello, {{decisionMaker}}? This is {{callerName}} from {{companyName}}. We clean several daycares and early-childhood centres in {{city}}, with child-safe products and disinfection that meets the ministry''s requirements. I''m calling to see how things are going on your side at {{businessName}}. Do you have two minutes?"}, {"key": "questions", "title": "Qualifying questions", "body": "1. Who does the cleaning right now: a company, or your educators at the end of the day?\n2. Is it cleaned every evening? And how often are toys and surfaces disinfected?\n3. Roughly how many facilities and rooms do you have?\n4. Are the products used fragrance-free and child-safe? Do parents ever ask about it?\n5. Until when does your current agreement run, and who reviews it: you or the board?\n6. What would make you say the service is really good: consistency, disinfection, schedules?"}, {"key": "objections", "title": "Objections and replies", "body": "« Our educators do the cleaning. »\n→ That''s common, but it takes time away from the children and disinfection is rarely complete. We could take the evening cleaning and disinfection, and they would only keep the daytime tidying.\n\n« We already have someone. »\n→ Perfect, that''s normal: most of our clients already had a provider. I''m not asking you to switch today, only to let you see what we would offer so you can compare when your agreement comes up for renewal. Roughly when is that?\n\n« We don''t have a budget for this. »\n→ I understand. We often land at the budget you already have, sometimes below it, because we adjust the frequency and the tasks to what you actually need. A twenty-minute visit would let us give you an exact number, no commitment.\n\n« Send me an email. »\n→ Gladly. So it''s useful and not a generic email, may I ask you two quick questions? How often is the cleaning done, and roughly what size is the space? I''ll send it today. Which address should I use?\n\n« I don''t have time. »\n→ I understand, I''ll be brief. Would Tuesday or Thursday morning work better for five minutes?\n\n« Call me back later. »\n→ Of course. So I don''t catch you at a bad time, which day and time work best? I''ll note it and call you back exactly then.\n\n« Head office decides that. »\n→ Understood. Who would be the right person there, and may I mention we spoke? On your side, are you happy with the current service? Your opinion matters when head office reviews contracts.\n\n« The board decides that. »\n→ Of course. I can prepare a clear quote that you can present to the board. Do you know when they meet next?"}, {"key": "closing", "title": "Close: propose the site visit", "body": "Here''s what I suggest, and it''s simple: we come by for a free twenty-minute visit of your premises, and you receive a detailed quote within 48 hours, with no commitment whatsoever. Would Tuesday morning or Thursday afternoon work for you?\n[Confirm the address, the contact name and the email.]\nThank you {{decisionMaker}}, talk soon."}]'::jsonb,
    true, CURRENT_TIMESTAMP);

INSERT INTO call_scripts (id, name, business_type, language, sections, active, updated_at) VALUES (
    gen_random_uuid(), 'Script cliniques et cabinets', 'Clinique', 'fr',
    '[{"key": "opening", "title": "Ouverture", "body": "Bonjour, {{decisionMaker}}? Ici {{callerName}} de {{companyName}}. On s''occupe de l''entretien de cliniques médicales et dentaires à {{city}}, avec des protocoles de désinfection pour les salles d''examen et les aires d''attente. Je voulais savoir comment c''est organisé chez {{businessName}}. Je vous prends à un bon moment?"}, {"key": "questions", "title": "Questions de qualification", "body": "1. Qui fait l''entretien actuellement, et à quelle heure : après la fermeture ou tôt le matin?\n2. Combien de salles d''examen ou de traitement avez-vous, et quelle superficie environ?\n3. Est-ce que vos besoins en désinfection sont couverts : surfaces à haut contact, salles de bain, aire d''attente?\n4. Avez-vous déjà eu des commentaires de patients ou du personnel sur la propreté?\n5. Jusqu''à quand votre entente actuelle vous engage-t-elle?\n6. Est-ce que les produits utilisés doivent répondre à des normes précises de votre ordre professionnel?"}, {"key": "objections", "title": "Objections et réponses", "body": "« On a besoin de quelqu''un de certifié pour le médical. »\n→ Justement : notre équipe est formée à la désinfection en milieu de soins et on documente ce qui est fait à chaque passage. Je peux vous envoyer notre protocole avec la soumission.\n\n« On a déjà quelqu''un. »\n→ Parfait, c''est normal : la plupart de nos clients avaient déjà un fournisseur. Je ne vous demande pas de changer aujourd''hui, seulement de savoir ce qu''on vous offrirait pour comparer quand votre entente viendra à échéance. C''est quand, à peu près?\n\n« On n''a pas de budget pour ça. »\n→ Je comprends. Souvent on arrive au même budget que vous avez déjà, parfois en dessous, parce qu''on ajuste la fréquence et les tâches à vos vrais besoins. Une visite de vingt minutes nous permettrait de vous donner un chiffre précis, sans engagement.\n\n« Envoyez-moi un courriel. »\n→ Avec plaisir. Pour que ce soit utile et pas un courriel générique, je peux vous poser deux petites questions? À quelle fréquence le ménage est fait, et environ quelle superficie? Je vous l''envoie aujourd''hui, à quelle adresse?\n\n« Je n''ai pas le temps. »\n→ Je comprends, je serai bref. Est-ce que mardi ou jeudi en avant-midi vous conviendrait mieux pour cinq minutes?\n\n« Rappelez-moi plus tard. »\n→ Certainement. Pour ne pas vous déranger au mauvais moment, quel jour et quelle heure sont les meilleurs pour vous? Je le note et je vous rappelle exactement à ce moment-là.\n\n« C''est le siège social qui décide. »\n→ D''accord. Qui serait la bonne personne là-bas, et puis-je mentionner que je vous ai parlé? De votre côté, est-ce que la qualité du service actuel vous convient? Votre avis compte quand le siège social révise les contrats.\n\n« On est satisfaits de notre fournisseur. »\n→ Tant mieux, c''est rare! Dans ce cas, gardez simplement nos coordonnées : si un jour la qualité baisse ou que les prix montent, vous aurez une option déjà évaluée. Puis-je vous envoyer un court résumé de nos services?"}, {"key": "closing", "title": "Conclusion : proposer la visite", "body": "Ce que je vous propose est simple : on passe faire une visite gratuite de vos locaux, vingt minutes, et vous recevez une soumission détaillée dans les 48 heures, sans aucun engagement. Est-ce que mardi en avant-midi ou jeudi en après-midi vous conviendrait?\n[Confirmer l''adresse, le nom de la personne et le courriel.]\nMerci {{decisionMaker}}, à bientôt."}]'::jsonb,
    true, CURRENT_TIMESTAMP);

INSERT INTO call_scripts (id, name, business_type, language, sections, active, updated_at) VALUES (
    gen_random_uuid(), 'Clinic and medical office script', 'Clinique', 'en',
    '[{"key": "opening", "title": "Opening", "body": "Hello, {{decisionMaker}}? This is {{callerName}} from {{companyName}}. We clean medical and dental clinics in {{city}}, with disinfection protocols for exam rooms and waiting areas. I wanted to know how it''s organised at {{businessName}}. Is this a good time?"}, {"key": "questions", "title": "Qualifying questions", "body": "1. Who does the cleaning right now, and when: after closing or early morning?\n2. How many exam or treatment rooms do you have, and roughly what size is the clinic?\n3. Are your disinfection needs covered: high-touch surfaces, washrooms, waiting area?\n4. Have you ever had comments from patients or staff about cleanliness?\n5. Until when does your current agreement run?\n6. Do the products used need to meet specific standards from your professional order?"}, {"key": "objections", "title": "Objections and replies", "body": "« We need someone certified for medical settings. »\n→ Exactly: our team is trained in healthcare disinfection and we document what is done at each visit. I can send you our protocol along with the quote.\n\n« We already have someone. »\n→ Perfect, that''s normal: most of our clients already had a provider. I''m not asking you to switch today, only to let you see what we would offer so you can compare when your agreement comes up for renewal. Roughly when is that?\n\n« We don''t have a budget for this. »\n→ I understand. We often land at the budget you already have, sometimes below it, because we adjust the frequency and the tasks to what you actually need. A twenty-minute visit would let us give you an exact number, no commitment.\n\n« Send me an email. »\n→ Gladly. So it''s useful and not a generic email, may I ask you two quick questions? How often is the cleaning done, and roughly what size is the space? I''ll send it today. Which address should I use?\n\n« I don''t have time. »\n→ I understand, I''ll be brief. Would Tuesday or Thursday morning work better for five minutes?\n\n« Call me back later. »\n→ Of course. So I don''t catch you at a bad time, which day and time work best? I''ll note it and call you back exactly then.\n\n« Head office decides that. »\n→ Understood. Who would be the right person there, and may I mention we spoke? On your side, are you happy with the current service? Your opinion matters when head office reviews contracts.\n\n« We''re happy with our provider. »\n→ Good to hear, that''s rare! In that case, simply keep our contact details: if quality ever drops or prices go up, you''ll have an option already evaluated. May I send you a short summary of our services?"}, {"key": "closing", "title": "Close: propose the site visit", "body": "Here''s what I suggest, and it''s simple: we come by for a free twenty-minute visit of your premises, and you receive a detailed quote within 48 hours, with no commitment whatsoever. Would Tuesday morning or Thursday afternoon work for you?\n[Confirm the address, the contact name and the email.]\nThank you {{decisionMaker}}, talk soon."}]'::jsonb,
    true, CURRENT_TIMESTAMP);

INSERT INTO call_scripts (id, name, business_type, language, sections, active, updated_at) VALUES (
    gen_random_uuid(), 'Script restaurants', 'Restaurant', 'fr',
    '[{"key": "opening", "title": "Ouverture", "body": "Bonjour, {{decisionMaker}}? Ici {{callerName}} de {{companyName}}. On fait l''entretien de nuit de restaurants à {{city}} : salle à manger, salles de bain, dégraissage de cuisine. Je sais que vous êtes occupé, je serai bref. Comment ça se passe chez {{businessName}} pour le ménage en ce moment?"}, {"key": "questions", "title": "Questions de qualification", "body": "1. Qui fait le ménage en fin de soirée : votre équipe de cuisine ou une compagnie?\n2. Vous fermez à quelle heure? Est-ce que l''entretien de nuit, après le service, vous conviendrait?\n3. Est-ce que le dégraissage de la cuisine et des hottes est fait régulièrement?\n4. Avez-vous déjà eu une visite de la MAPAQ ou des commentaires de clients sur la propreté des salles de bain?\n5. Combien de places assises avez-vous, environ?\n6. Jusqu''à quand votre entente actuelle vous engage-t-elle?"}, {"key": "objections", "title": "Objections et réponses", "body": "« Ma brigade fait le ménage à la fermeture. »\n→ C''est le cas presque partout, mais à 23 h votre monde est fatigué et le ménage est vite fait. Si on prend la nuit, votre équipe part plus tôt et vous ouvrez le matin dans une salle impeccable.\n\n« On a déjà quelqu''un. »\n→ Parfait, c''est normal : la plupart de nos clients avaient déjà un fournisseur. Je ne vous demande pas de changer aujourd''hui, seulement de savoir ce qu''on vous offrirait pour comparer quand votre entente viendra à échéance. C''est quand, à peu près?\n\n« On n''a pas de budget pour ça. »\n→ Je comprends. Souvent on arrive au même budget que vous avez déjà, parfois en dessous, parce qu''on ajuste la fréquence et les tâches à vos vrais besoins. Une visite de vingt minutes nous permettrait de vous donner un chiffre précis, sans engagement.\n\n« Envoyez-moi un courriel. »\n→ Avec plaisir. Pour que ce soit utile et pas un courriel générique, je peux vous poser deux petites questions? À quelle fréquence le ménage est fait, et environ quelle superficie? Je vous l''envoie aujourd''hui, à quelle adresse?\n\n« Je n''ai pas le temps. »\n→ Je comprends, je serai bref. Est-ce que mardi ou jeudi en avant-midi vous conviendrait mieux pour cinq minutes?\n\n« Rappelez-moi plus tard. »\n→ Certainement. Pour ne pas vous déranger au mauvais moment, quel jour et quelle heure sont les meilleurs pour vous? Je le note et je vous rappelle exactement à ce moment-là.\n\n« C''est le propriétaire ou la franchise qui décide. »\n→ D''accord. Qui serait la bonne personne, et puis-je mentionner que je vous ai parlé? De votre côté, est-ce que la propreté le matin vous convient?"}, {"key": "closing", "title": "Conclusion : proposer la visite", "body": "Ce que je vous propose : je passe en après-midi avant votre service, quinze minutes, pour voir la salle et la cuisine, et vous avez une soumission détaillée dans les 48 heures, sans engagement. Mardi ou mercredi vers 14 h, ça vous conviendrait?\n[Confirmer l''adresse, le nom et le courriel.]\nMerci {{decisionMaker}}, bon service!"}]'::jsonb,
    true, CURRENT_TIMESTAMP);

INSERT INTO call_scripts (id, name, business_type, language, sections, active, updated_at) VALUES (
    gen_random_uuid(), 'Restaurant script', 'Restaurant', 'en',
    '[{"key": "opening", "title": "Opening", "body": "Hello, {{decisionMaker}}? This is {{callerName}} from {{companyName}}. We do overnight cleaning for restaurants in {{city}}: dining room, washrooms, kitchen degreasing. I know you''re busy, I''ll be brief. How is the cleaning handled at {{businessName}} right now?"}, {"key": "questions", "title": "Qualifying questions", "body": "1. Who does the cleaning at the end of the night: your kitchen crew or a company?\n2. What time do you close? Would overnight cleaning, after service, suit you?\n3. Is the kitchen and hood degreasing done regularly?\n4. Have you ever had a MAPAQ inspection or customer comments about the washrooms?\n5. Roughly how many seats do you have?\n6. Until when does your current agreement run?"}, {"key": "objections", "title": "Objections and replies", "body": "« My crew cleans at closing. »\n→ That''s the case almost everywhere, but at 11 p.m. your people are tired and the cleaning is rushed. If we take the night, your team leaves earlier and you open in the morning to a spotless room.\n\n« We already have someone. »\n→ Perfect, that''s normal: most of our clients already had a provider. I''m not asking you to switch today, only to let you see what we would offer so you can compare when your agreement comes up for renewal. Roughly when is that?\n\n« We don''t have a budget for this. »\n→ I understand. We often land at the budget you already have, sometimes below it, because we adjust the frequency and the tasks to what you actually need. A twenty-minute visit would let us give you an exact number, no commitment.\n\n« Send me an email. »\n→ Gladly. So it''s useful and not a generic email, may I ask you two quick questions? How often is the cleaning done, and roughly what size is the space? I''ll send it today. Which address should I use?\n\n« I don''t have time. »\n→ I understand, I''ll be brief. Would Tuesday or Thursday morning work better for five minutes?\n\n« Call me back later. »\n→ Of course. So I don''t catch you at a bad time, which day and time work best? I''ll note it and call you back exactly then.\n\n« The owner or the franchise decides. »\n→ Understood. Who would be the right person, and may I mention we spoke? On your side, are you happy with how clean the place is in the morning?"}, {"key": "closing", "title": "Close: propose the site visit", "body": "Here''s what I suggest: I come by in the afternoon before service, fifteen minutes, to see the dining room and the kitchen, and you have a detailed quote within 48 hours, no commitment. Would Tuesday or Wednesday around 2 p.m. work?\n[Confirm the address, the contact name and the email.]\nThank you {{decisionMaker}}, have a good service!"}]'::jsonb,
    true, CURRENT_TIMESTAMP);

INSERT INTO call_scripts (id, name, business_type, language, sections, active, updated_at) VALUES (
    gen_random_uuid(), 'Script bureaux', 'Bureau', 'fr',
    '[{"key": "opening", "title": "Ouverture", "body": "Bonjour, {{decisionMaker}}? Ici {{callerName}} de {{companyName}}, entretien ménager de bureaux à {{city}}. On travaille avec des cabinets et des bureaux comme {{businessName}} qui veulent un service constant, en soirée, sans avoir à le gérer. Je vous prends à un bon moment, deux minutes?"}, {"key": "questions", "title": "Questions de qualification", "body": "1. Qui s''occupe de l''entretien de vos bureaux en ce moment, et à quelle fréquence?\n2. Combien d''employés travaillent sur place, et environ quelle superficie?\n3. Le ménage se fait en soirée ou pendant les heures de bureau?\n4. Est-ce que le service est constant, ou ça varie selon qui vient?\n5. Jusqu''à quand votre entente actuelle vous engage-t-elle?\n6. Qu''est-ce qui compte le plus pour vous : la fiabilité, la discrétion, le prix, les produits écologiques?"}, {"key": "objections", "title": "Objections et réponses", "body": "« Le concierge de l''immeuble s''en occupe. »\n→ Souvent le concierge couvre les aires communes, mais pas vos bureaux, vos salles de conférence ni votre cuisinette. Voulez-vous qu''on regarde ce qui n''est pas couvert?\n\n« On a déjà quelqu''un. »\n→ Parfait, c''est normal : la plupart de nos clients avaient déjà un fournisseur. Je ne vous demande pas de changer aujourd''hui, seulement de savoir ce qu''on vous offrirait pour comparer quand votre entente viendra à échéance. C''est quand, à peu près?\n\n« On n''a pas de budget pour ça. »\n→ Je comprends. Souvent on arrive au même budget que vous avez déjà, parfois en dessous, parce qu''on ajuste la fréquence et les tâches à vos vrais besoins. Une visite de vingt minutes nous permettrait de vous donner un chiffre précis, sans engagement.\n\n« Envoyez-moi un courriel. »\n→ Avec plaisir. Pour que ce soit utile et pas un courriel générique, je peux vous poser deux petites questions? À quelle fréquence le ménage est fait, et environ quelle superficie? Je vous l''envoie aujourd''hui, à quelle adresse?\n\n« Je n''ai pas le temps. »\n→ Je comprends, je serai bref. Est-ce que mardi ou jeudi en avant-midi vous conviendrait mieux pour cinq minutes?\n\n« Rappelez-moi plus tard. »\n→ Certainement. Pour ne pas vous déranger au mauvais moment, quel jour et quelle heure sont les meilleurs pour vous? Je le note et je vous rappelle exactement à ce moment-là.\n\n« C''est le siège social qui décide. »\n→ D''accord. Qui serait la bonne personne là-bas, et puis-je mentionner que je vous ai parlé? De votre côté, est-ce que la qualité du service actuel vous convient? Votre avis compte quand le siège social révise les contrats.\n\n« On est satisfaits de notre fournisseur. »\n→ Tant mieux, c''est rare! Dans ce cas, gardez simplement nos coordonnées : si un jour la qualité baisse ou que les prix montent, vous aurez une option déjà évaluée. Puis-je vous envoyer un court résumé de nos services?"}, {"key": "closing", "title": "Conclusion : proposer la visite", "body": "Ce que je vous propose est simple : on passe faire une visite gratuite de vos locaux, vingt minutes, et vous recevez une soumission détaillée dans les 48 heures, sans aucun engagement. Est-ce que mardi en avant-midi ou jeudi en après-midi vous conviendrait?\n[Confirmer l''adresse, le nom de la personne et le courriel.]\nMerci {{decisionMaker}}, à bientôt."}]'::jsonb,
    true, CURRENT_TIMESTAMP);

INSERT INTO call_scripts (id, name, business_type, language, sections, active, updated_at) VALUES (
    gen_random_uuid(), 'Office script', 'Bureau', 'en',
    '[{"key": "opening", "title": "Opening", "body": "Hello, {{decisionMaker}}? This is {{callerName}} from {{companyName}}, office cleaning in {{city}}. We work with firms and offices like {{businessName}} that want consistent evening service without having to manage it. Is this a good time, two minutes?"}, {"key": "questions", "title": "Qualifying questions", "body": "1. Who takes care of cleaning your offices right now, and how often?\n2. How many employees work on site, and roughly what size is the space?\n3. Is the cleaning done in the evening or during office hours?\n4. Is the service consistent, or does it vary depending on who comes?\n5. Until when does your current agreement run?\n6. What matters most to you: reliability, discretion, price, eco-friendly products?"}, {"key": "objections", "title": "Objections and replies", "body": "« The building''s janitor takes care of it. »\n→ The janitor often covers the common areas, but not your offices, meeting rooms or kitchenette. Would you like us to look at what isn''t covered?\n\n« We already have someone. »\n→ Perfect, that''s normal: most of our clients already had a provider. I''m not asking you to switch today, only to let you see what we would offer so you can compare when your agreement comes up for renewal. Roughly when is that?\n\n« We don''t have a budget for this. »\n→ I understand. We often land at the budget you already have, sometimes below it, because we adjust the frequency and the tasks to what you actually need. A twenty-minute visit would let us give you an exact number, no commitment.\n\n« Send me an email. »\n→ Gladly. So it''s useful and not a generic email, may I ask you two quick questions? How often is the cleaning done, and roughly what size is the space? I''ll send it today. Which address should I use?\n\n« I don''t have time. »\n→ I understand, I''ll be brief. Would Tuesday or Thursday morning work better for five minutes?\n\n« Call me back later. »\n→ Of course. So I don''t catch you at a bad time, which day and time work best? I''ll note it and call you back exactly then.\n\n« Head office decides that. »\n→ Understood. Who would be the right person there, and may I mention we spoke? On your side, are you happy with the current service? Your opinion matters when head office reviews contracts.\n\n« We''re happy with our provider. »\n→ Good to hear, that''s rare! In that case, simply keep our contact details: if quality ever drops or prices go up, you''ll have an option already evaluated. May I send you a short summary of our services?"}, {"key": "closing", "title": "Close: propose the site visit", "body": "Here''s what I suggest, and it''s simple: we come by for a free twenty-minute visit of your premises, and you receive a detailed quote within 48 hours, with no commitment whatsoever. Would Tuesday morning or Thursday afternoon work for you?\n[Confirm the address, the contact name and the email.]\nThank you {{decisionMaker}}, talk soon."}]'::jsonb,
    true, CURRENT_TIMESTAMP);

INSERT INTO call_scripts (id, name, business_type, language, sections, active, updated_at) VALUES (
    gen_random_uuid(), 'Script chantiers (après construction)', 'Chantier', 'fr',
    '[{"key": "opening", "title": "Ouverture", "body": "Bonjour, {{decisionMaker}}? Ici {{callerName}} de {{companyName}}. On fait le nettoyage après construction à {{city}} : poussière fine, vitres, planchers, prêt pour la livraison. J''ai vu que vous avez un chantier à {{businessName}}. Vous avez prévu quelqu''un pour le nettoyage final?"}, {"key": "questions", "title": "Questions de qualification", "body": "1. À quelle étape est le chantier, et quelle est la date de livraison prévue?\n2. Combien de pieds carrés environ, et combien d''étages ou d''unités?\n3. Le nettoyage final est-il prévu dans votre devis, ou c''est le sous-traitant qui le fait?\n4. Avez-vous besoin d''un nettoyage grossier pendant les travaux, ou seulement du final?\n5. Qui prend la décision pour les sous-traitants : vous, le chargé de projet ou le client?\n6. Avez-vous d''autres chantiers à venir dans les prochains mois?"}, {"key": "objections", "title": "Objections et réponses", "body": "« Nos gars font le nettoyage eux-mêmes. »\n→ C''est courant, mais à 40 $ de l''heure un menuisier qui passe la vadrouille, ça coûte cher et le client voit la différence à la livraison. On arrive avec l''équipement et on libère vos gars pour finir le travail.\n\n« Le client s''en occupe. »\n→ Parfait. Voulez-vous que je lui envoie une soumission en mentionnant que vous avez recommandé qu''on se parle? Ça vous évite des appels de dernière minute.\n\n« On n''a pas de budget pour ça. »\n→ Je comprends. Souvent on arrive au même budget que vous avez déjà, parfois en dessous, parce qu''on ajuste la fréquence et les tâches à vos vrais besoins. Une visite de vingt minutes nous permettrait de vous donner un chiffre précis, sans engagement.\n\n« Envoyez-moi un courriel. »\n→ Avec plaisir. Pour que ce soit utile et pas un courriel générique, je peux vous poser deux petites questions? À quelle fréquence le ménage est fait, et environ quelle superficie? Je vous l''envoie aujourd''hui, à quelle adresse?\n\n« Je n''ai pas le temps. »\n→ Je comprends, je serai bref. Est-ce que mardi ou jeudi en avant-midi vous conviendrait mieux pour cinq minutes?\n\n« Rappelez-moi plus tard. »\n→ Certainement. Pour ne pas vous déranger au mauvais moment, quel jour et quelle heure sont les meilleurs pour vous? Je le note et je vous rappelle exactement à ce moment-là."}, {"key": "closing", "title": "Conclusion : proposer la visite", "body": "Ce que je vous propose : je passe sur le chantier, quinze minutes, pour évaluer les surfaces, et vous avez un prix ferme dans les 48 heures. Quand est-ce que je peux passer? Et pour vos prochains chantiers, on peut convenir d''un tarif au pied carré.\n[Confirmer l''adresse du chantier, le nom et le courriel.]\nMerci {{decisionMaker}}, à bientôt."}]'::jsonb,
    true, CURRENT_TIMESTAMP);

INSERT INTO call_scripts (id, name, business_type, language, sections, active, updated_at) VALUES (
    gen_random_uuid(), 'Construction site (post-construction) script', 'Chantier', 'en',
    '[{"key": "opening", "title": "Opening", "body": "Hello, {{decisionMaker}}? This is {{callerName}} from {{companyName}}. We do post-construction cleaning in {{city}}: fine dust, windows, floors, ready for handover. I saw you have a project at {{businessName}}. Do you have someone lined up for the final clean?"}, {"key": "questions", "title": "Qualifying questions", "body": "1. What stage is the project at, and what is the planned delivery date?\n2. Roughly how many square feet, and how many floors or units?\n3. Is the final clean included in your estimate, or does the subcontractor do it?\n4. Do you need a rough clean during the work, or only the final one?\n5. Who decides on subcontractors: you, the project manager or the client?\n6. Do you have other projects coming up in the next few months?"}, {"key": "objections", "title": "Objections and replies", "body": "« Our guys do the cleaning themselves. »\n→ That''s common, but at $40 an hour a carpenter pushing a mop is expensive, and the client sees the difference at handover. We come with the equipment and free your guys to finish the work.\n\n« The client takes care of it. »\n→ Perfect. Would you like me to send them a quote mentioning that you suggested we talk? It saves you last-minute calls.\n\n« We don''t have a budget for this. »\n→ I understand. We often land at the budget you already have, sometimes below it, because we adjust the frequency and the tasks to what you actually need. A twenty-minute visit would let us give you an exact number, no commitment.\n\n« Send me an email. »\n→ Gladly. So it''s useful and not a generic email, may I ask you two quick questions? How often is the cleaning done, and roughly what size is the space? I''ll send it today. Which address should I use?\n\n« I don''t have time. »\n→ I understand, I''ll be brief. Would Tuesday or Thursday morning work better for five minutes?\n\n« Call me back later. »\n→ Of course. So I don''t catch you at a bad time, which day and time work best? I''ll note it and call you back exactly then."}, {"key": "closing", "title": "Close: propose the site visit", "body": "Here''s what I suggest: I come by the site, fifteen minutes, to assess the surfaces, and you have a firm price within 48 hours. When can I come by? And for your next projects, we can agree on a per-square-foot rate.\n[Confirm the site address, the contact name and the email.]\nThank you {{decisionMaker}}, talk soon."}]'::jsonb,
    true, CURRENT_TIMESTAMP);

-- 8. Seed: email templates
INSERT INTO email_templates (id, template_key, language, subject, body, active, updated_at) VALUES (
    gen_random_uuid(), 'quote_followup', 'fr', 'Votre soumission d''entretien ménager – {{businessName}}',
    'Bonjour {{decisionMaker}},

Merci pour notre conversation d''aujourd''hui. Comme convenu, voici notre estimation pour l''entretien de {{businessName}} :

{{estimateLines}}
Total : {{estimateTotal}}

Ce prix comprend la main-d''œuvre, les produits et l''équipement. Il sera confirmé après une courte visite de vos locaux, sans engagement.

{{extraNote}}
Je reste disponible pour toute question.

{{callerName}}
{{companyName}}',
    true, CURRENT_TIMESTAMP);

INSERT INTO email_templates (id, template_key, language, subject, body, active, updated_at) VALUES (
    gen_random_uuid(), 'quote_followup', 'en', 'Your cleaning quote – {{businessName}}',
    'Hello {{decisionMaker}},

Thank you for our conversation today. As agreed, here is our estimate for cleaning {{businessName}}:

{{estimateLines}}
Total: {{estimateTotal}}

This price includes labour, products and equipment. It will be confirmed after a short visit of your premises, with no commitment.

{{extraNote}}
I remain available for any questions.

{{callerName}}
{{companyName}}',
    true, CURRENT_TIMESTAMP);

INSERT INTO email_templates (id, template_key, language, subject, body, active, updated_at) VALUES (
    gen_random_uuid(), 'visit_confirmation', 'fr', 'Confirmation de visite – {{businessName}}',
    'Bonjour {{decisionMaker}},

Je vous confirme notre visite à {{businessName}} le {{visitAt}}.

La visite dure environ 20 minutes : nous regardons les espaces à entretenir et vous recevez une soumission détaillée dans les 48 heures.

Une invitation est jointe à ce courriel pour votre agenda. Si l''heure ne vous convient plus, répondez simplement à ce message.

{{extraNote}}
Au plaisir de vous rencontrer,

{{callerName}}
{{companyName}}',
    true, CURRENT_TIMESTAMP);

INSERT INTO email_templates (id, template_key, language, subject, body, active, updated_at) VALUES (
    gen_random_uuid(), 'visit_confirmation', 'en', 'Visit confirmation – {{businessName}}',
    'Hello {{decisionMaker}},

This confirms our visit to {{businessName}} on {{visitAt}}.

The visit takes about 20 minutes: we look at the areas to be cleaned and you receive a detailed quote within 48 hours.

A calendar invitation is attached to this email. If the time no longer suits you, simply reply to this message.

{{extraNote}}
Looking forward to meeting you,

{{callerName}}
{{companyName}}',
    true, CURRENT_TIMESTAMP);

INSERT INTO email_templates (id, template_key, language, subject, body, active, updated_at) VALUES (
    gen_random_uuid(), 'missed_you', 'fr', 'J''ai tenté de vous joindre – {{companyName}}',
    'Bonjour {{decisionMaker}},

J''ai tenté de vous joindre aujourd''hui au sujet de l''entretien ménager de {{businessName}}.

{{companyName}} offre un service d''entretien commercial fiable, en soirée, avec des produits écologiques. Nous proposons une visite gratuite de vos locaux suivie d''une soumission détaillée, sans engagement.

Quel serait le meilleur moment pour vous rappeler? Vous pouvez aussi simplement répondre à ce courriel.

{{extraNote}}
Bonne journée,

{{callerName}}
{{companyName}}',
    true, CURRENT_TIMESTAMP);

INSERT INTO email_templates (id, template_key, language, subject, body, active, updated_at) VALUES (
    gen_random_uuid(), 'missed_you', 'en', 'I tried to reach you – {{companyName}}',
    'Hello {{decisionMaker}},

I tried to reach you today about the cleaning of {{businessName}}.

{{companyName}} offers reliable commercial cleaning, in the evening, with eco-friendly products. We propose a free visit of your premises followed by a detailed quote, with no commitment.

When would be the best time to call you back? You can also simply reply to this email.

{{extraNote}}
Have a good day,

{{callerName}}
{{companyName}}',
    true, CURRENT_TIMESTAMP);

INSERT INTO email_templates (id, template_key, language, subject, body, active, updated_at) VALUES (
    gen_random_uuid(), 'intro', 'fr', 'Entretien ménager pour {{businessName}}',
    'Bonjour {{decisionMaker}},

Comme discuté au téléphone, voici en quelques lignes ce que {{companyName}} peut faire pour {{businessName}} :

- Entretien régulier (quotidien, hebdomadaire ou aux deux semaines) en dehors de vos heures d''ouverture
- Désinfection des surfaces à haut contact et des salles de bain
- Produits écologiques, sans parfum, sans danger pour vos employés et vos clients
- Un responsable attitré et un suivi après chaque passage

La prochaine étape est une visite gratuite de 20 minutes pour vous remettre une soumission précise.

{{extraNote}}
Au plaisir,

{{callerName}}
{{companyName}}',
    true, CURRENT_TIMESTAMP);

INSERT INTO email_templates (id, template_key, language, subject, body, active, updated_at) VALUES (
    gen_random_uuid(), 'intro', 'en', 'Cleaning services for {{businessName}}',
    'Hello {{decisionMaker}},

As discussed on the phone, here in a few lines is what {{companyName}} can do for {{businessName}}:

- Regular cleaning (daily, weekly or every two weeks) outside your opening hours
- Disinfection of high-touch surfaces and washrooms
- Eco-friendly, fragrance-free products that are safe for your employees and customers
- A dedicated supervisor and a follow-up after each visit

The next step is a free 20-minute visit so we can give you an exact quote.

{{extraNote}}
Best regards,

{{callerName}}
{{companyName}}',
    true, CURRENT_TIMESTAMP);

