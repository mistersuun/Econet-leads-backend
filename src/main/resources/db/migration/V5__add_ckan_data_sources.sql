-- V5: Add all CKAN-based data sources with JSON configurations
-- This allows the GenericCkanImportService to handle imports without code changes

-- 1. Entreprises d'économie sociale
INSERT INTO data_sources (id, source_name, source_type, source_url, sync_frequency, active, config, created_at)
VALUES (
    gen_random_uuid(),
    'Données Québec - Économie Sociale',
    'CKAN_API',
    'https://www.donneesquebec.ca/recherche/dataset/entreprises-economie-sociale',
    'MONTHLY',
    true,
    '{
        "resourceId": "497c9f58-5b5d-4dda-ba50-5b4e0e7991d4",
        "ckanBaseUrl": "https://www.donneesquebec.ca/recherche/api/3/action/",
        "batchSize": 100,
        "businessType": "Économie Sociale",
        "fieldMapping": {
            "businessName": "nom",
            "addressStreet": "adresse",
            "addressCity": "ville",
            "postalCode": "code_postal",
            "phone": "telephone",
            "email": "courriel",
            "externalId": "_id"
        }
    }',
    CURRENT_TIMESTAMP
);

-- 2. Centres de formation professionnelle
INSERT INTO data_sources (id, source_name, source_type, source_url, sync_frequency, active, config, created_at)
VALUES (
    gen_random_uuid(),
    'Données Québec - Formation Professionnelle',
    'CKAN_API',
    'https://www.donneesquebec.ca/recherche/dataset/centres-de-formation-professionnelle',
    'MONTHLY',
    true,
    '{
        "resourceId": "4c3f5f48-b0c3-41a3-bb7c-c027e31ed6fd",
        "ckanBaseUrl": "https://www.donneesquebec.ca/recherche/api/3/action/",
        "batchSize": 100,
        "businessType": "Formation Professionnelle",
        "fieldMapping": {
            "businessName": "nom",
            "addressStreet": "adresse",
            "addressCity": "ville",
            "phone": "telephone",
            "website": "site_web",
            "externalId": "_id"
        }
    }',
    CURRENT_TIMESTAMP
);

-- 3. Hébergements touristiques (CITQ)
INSERT INTO data_sources (id, source_name, source_type, source_url, sync_frequency, active, config, created_at)
VALUES (
    gen_random_uuid(),
    'Données Québec - Hébergements Touristiques',
    'CKAN_API',
    'https://www.donneesquebec.ca/recherche/dataset/hebergements-touristiques',
    'MONTHLY',
    true,
    '{
        "resourceId": "8c3a1c07-63ff-4b7e-9304-28519f9ff54e",
        "ckanBaseUrl": "https://www.donneesquebec.ca/recherche/api/3/action/",
        "batchSize": 100,
        "businessType": "Hébergement Touristique",
        "fieldMapping": {
            "businessName": "nom_etablissement",
            "addressStreet": "adresse",
            "addressCity": "ville",
            "postalCode": "code_postal",
            "phone": "telephone",
            "website": "site_web",
            "externalId": "_id"
        }
    }',
    CURRENT_TIMESTAMP
);

-- 4. EÉSAD (Entreprises d'économie sociale en aide domestique)
INSERT INTO data_sources (id, source_name, source_type, source_url, sync_frequency, active, config, created_at)
VALUES (
    gen_random_uuid(),
    'Données Québec - EÉSAD',
    'CKAN_API',
    'https://www.donneesquebec.ca/recherche/dataset/eesad',
    'MONTHLY',
    true,
    '{
        "resourceId": "5d2e8e6b-6a7f-4f14-b83f-4ad5a8074f77",
        "ckanBaseUrl": "https://www.donneesquebec.ca/recherche/api/3/action/",
        "batchSize": 100,
        "businessType": "EÉSAD",
        "fieldMapping": {
            "businessName": "nom",
            "addressStreet": "adresse",
            "addressCity": "ville",
            "postalCode": "code_postal",
            "phone": "telephone",
            "email": "courriel",
            "externalId": "_id"
        }
    }',
    CURRENT_TIMESTAMP
);

-- 5. Coopératives et mutuelles
INSERT INTO data_sources (id, source_name, source_type, source_url, sync_frequency, active, config, created_at)
VALUES (
    gen_random_uuid(),
    'Données Québec - Coopératives et Mutuelles',
    'CKAN_API',
    'https://www.donneesquebec.ca/recherche/dataset/cooperatives-et-mutuelles',
    'MONTHLY',
    true,
    '{
        "resourceId": "f1b365ab-2d3a-4847-871f-4bc0a38b9c77",
        "ckanBaseUrl": "https://www.donneesquebec.ca/recherche/api/3/action/",
        "batchSize": 100,
        "businessType": "Coopérative",
        "fieldMapping": {
            "businessName": "nom_legal",
            "addressStreet": "adresse",
            "addressCity": "ville",
            "postalCode": "code_postal",
            "externalId": "_id"
        }
    }',
    CURRENT_TIMESTAMP
);

-- 6. Organismes communautaires Famille
INSERT INTO data_sources (id, source_name, source_type, source_url, sync_frequency, active, config, created_at)
VALUES (
    gen_random_uuid(),
    'Données Québec - Organismes Famille',
    'CKAN_API',
    'https://www.donneesquebec.ca/recherche/dataset/organismes-famille',
    'MONTHLY',
    true,
    '{
        "resourceId": "cc5f0a7b-4638-437f-9bb2-41fb1a0eee7a",
        "ckanBaseUrl": "https://www.donneesquebec.ca/recherche/api/3/action/",
        "batchSize": 100,
        "businessType": "Organisme Famille",
        "fieldMapping": {
            "businessName": "organisme",
            "addressStreet": "adresse",
            "addressCity": "ville",
            "phone": "telephone",
            "email": "courriel",
            "externalId": "_id"
        }
    }',
    CURRENT_TIMESTAMP
);

-- 7. Établissements d'enseignement privés
INSERT INTO data_sources (id, source_name, source_type, source_url, sync_frequency, active, config, created_at)
VALUES (
    gen_random_uuid(),
    'Données Québec - Enseignement Privé',
    'CKAN_API',
    'https://www.donneesquebec.ca/recherche/dataset/etablissements-enseignement-prives',
    'MONTHLY',
    true,
    '{
        "resourceId": "b66de6cf-a1c8-4e56-9d45-5bf86de3973f",
        "ckanBaseUrl": "https://www.donneesquebec.ca/recherche/api/3/action/",
        "batchSize": 100,
        "businessType": "Enseignement Privé",
        "fieldMapping": {
            "businessName": "nom",
            "addressStreet": "adresse",
            "addressCity": "ville",
            "phone": "telephone",
            "externalId": "_id"
        }
    }',
    CURRENT_TIMESTAMP
);

-- 8. Services de garde en milieu scolaire
INSERT INTO data_sources (id, source_name, source_type, source_url, sync_frequency, active, config, created_at)
VALUES (
    gen_random_uuid(),
    'Données Québec - Services de Garde Scolaire',
    'CKAN_API',
    'https://www.donneesquebec.ca/recherche/dataset/services-de-garde-en-milieu-scolaire',
    'MONTHLY',
    true,
    '{
        "resourceId": "c80fbe32-86a3-4a82-9ee7-568e54714152",
        "ckanBaseUrl": "https://www.donneesquebec.ca/recherche/api/3/action/",
        "batchSize": 100,
        "businessType": "Service de Garde Scolaire",
        "fieldMapping": {
            "businessName": "nom",
            "addressStreet": "adresse",
            "addressCity": "ville",
            "phone": "telephone",
            "externalId": "_id"
        }
    }',
    CURRENT_TIMESTAMP
);

-- 9. Activités touristiques
INSERT INTO data_sources (id, source_name, source_type, source_url, sync_frequency, active, config, created_at)
VALUES (
    gen_random_uuid(),
    'Données Québec - Activités Touristiques',
    'CKAN_API',
    'https://www.donneesquebec.ca/recherche/dataset/activites-touristiques',
    'MONTHLY',
    true,
    '{
        "resourceId": "0cb77b52-6416-4b72-a7a0-0d8ed48f3fdc",
        "ckanBaseUrl": "https://www.donneesquebec.ca/recherche/api/3/action/",
        "batchSize": 100,
        "businessType": "Activité Touristique",
        "fieldMapping": {
            "businessName": "nom",
            "addressStreet": "adresse",
            "addressCity": "ville",
            "postalCode": "code_postal",
            "phone": "telephone",
            "website": "site_web",
            "externalId": "_id"
        }
    }',
    CURRENT_TIMESTAMP
);

-- 10. Coopératives agricoles
INSERT INTO data_sources (id, source_name, source_type, source_url, sync_frequency, active, config, created_at)
VALUES (
    gen_random_uuid(),
    'Données Québec - Coopératives Agricoles',
    'CKAN_API',
    'https://www.donneesquebec.ca/recherche/dataset/cooperatives-agricoles',
    'MONTHLY',
    true,
    '{
        "resourceId": "af2cdf5d-8d53-49d9-9f87-4eb916a4c67b",
        "ckanBaseUrl": "https://www.donneesquebec.ca/recherche/api/3/action/",
        "batchSize": 100,
        "businessType": "Coopérative Agricole",
        "fieldMapping": {
            "businessName": "nom",
            "addressStreet": "adresse",
            "addressCity": "ville",
            "phone": "telephone",
            "externalId": "_id"
        }
    }',
    CURRENT_TIMESTAMP
);

-- 11. Cliniques médicales (RAMQ)
INSERT INTO data_sources (id, source_name, source_type, source_url, sync_frequency, active, config, created_at)
VALUES (
    gen_random_uuid(),
    'Données Québec - Cliniques Médicales',
    'CKAN_API',
    'https://www.donneesquebec.ca/recherche/dataset/cliniques-medicaux',
    'MONTHLY',
    true,
    '{
        "resourceId": "c861cb47-9846-4b6b-92cd-673eee1268db",
        "ckanBaseUrl": "https://www.donneesquebec.ca/recherche/api/3/action/",
        "batchSize": 100,
        "businessType": "Clinique Médicale",
        "fieldMapping": {
            "businessName": "nom",
            "addressStreet": "adresse",
            "addressCity": "ville",
            "phone": "telephone",
            "externalId": "_id"
        }
    }',
    CURRENT_TIMESTAMP
);

-- 12. Résidences privées pour aînés
INSERT INTO data_sources (id, source_name, source_type, source_url, sync_frequency, active, config, created_at)
VALUES (
    gen_random_uuid(),
    'Données Québec - Résidences pour Aînés',
    'CKAN_API',
    'https://www.donneesquebec.ca/recherche/dataset/residences-privees-pour-aines',
    'MONTHLY',
    true,
    '{
        "resourceId": "e2a4ce37-481f-4203-8c75-8a6efcddb84e",
        "ckanBaseUrl": "https://www.donneesquebec.ca/recherche/api/3/action/",
        "batchSize": 100,
        "businessType": "Résidence pour Aînés",
        "fieldMapping": {
            "businessName": "nom",
            "addressStreet": "adresse",
            "addressCity": "ville",
            "postalCode": "code_postal",
            "phone": "telephone",
            "externalId": "_id"
        }
    }',
    CURRENT_TIMESTAMP
);

-- 13. Données Montréal - Permis établissements alimentaires
INSERT INTO data_sources (id, source_name, source_type, source_url, sync_frequency, active, config, created_at)
VALUES (
    gen_random_uuid(),
    'Données Montréal - Établissements Alimentaires',
    'CKAN_API',
    'https://donnees.montreal.ca/ville-de-montreal/permis-etablissements-alimentaires',
    'MONTHLY',
    true,
    '{
        "resourceId": "6c7ce9cc-0b5a-4cb8-b2cf-27bfc7c2ab26",
        "ckanBaseUrl": "https://donnees.montreal.ca/api/3/action/",
        "batchSize": 100,
        "businessType": "Établissement Alimentaire",
        "fieldMapping": {
            "businessName": "nom_etablissement",
            "addressStreet": "adresse",
            "addressCity": "ville",
            "addressProvinceDefault": "QC",
            "postalCode": "code_postal",
            "externalId": "_id"
        }
    }',
    CURRENT_TIMESTAMP
);

-- 14. Données Montréal - Entreprises d'économie sociale
INSERT INTO data_sources (id, source_name, source_type, source_url, sync_frequency, active, config, created_at)
VALUES (
    gen_random_uuid(),
    'Données Montréal - Économie Sociale',
    'CKAN_API',
    'https://donnees.montreal.ca/ville-de-montreal/entreprises-economie-sociale',
    'MONTHLY',
    true,
    '{
        "resourceId": "35d373fa-96d7-4f9c-9d1f-5f7c8c53d8d1",
        "ckanBaseUrl": "https://donnees.montreal.ca/api/3/action/",
        "batchSize": 100,
        "businessType": "Économie Sociale MTL",
        "fieldMapping": {
            "businessName": "nom",
            "addressStreet": "adresse",
            "addressCity": "ville",
            "phone": "telephone",
            "email": "courriel",
            "website": "site_web",
            "externalId": "_id"
        }
    }',
    CURRENT_TIMESTAMP
);

-- 15. Données Montréal - Services de garde en milieu familial
INSERT INTO data_sources (id, source_name, source_type, source_url, sync_frequency, active, config, created_at)
VALUES (
    gen_random_uuid(),
    'Données Montréal - Services de Garde',
    'CKAN_API',
    'https://donnees.montreal.ca/ville-de-montreal/services-garde-enfance',
    'MONTHLY',
    true,
    '{
        "resourceId": "dc7f1da1-5bad-4a33-8f6a-96f59c779843",
        "ckanBaseUrl": "https://donnees.montreal.ca/api/3/action/",
        "batchSize": 100,
        "businessType": "Service de Garde MTL",
        "fieldMapping": {
            "businessName": "nom",
            "addressStreet": "adresse",
            "addressCity": "ville",
            "phone": "telephone",
            "externalId": "_id"
        }
    }',
    CURRENT_TIMESTAMP
);

-- 16. Centres locaux d'emploi
INSERT INTO data_sources (id, source_name, source_type, source_url, sync_frequency, active, config, created_at)
VALUES (
    gen_random_uuid(),
    'Données Québec - Centres Locaux d''Emploi',
    'CKAN_API',
    'https://www.donneesquebec.ca/recherche/dataset/centres-locaux-emploi',
    'MONTHLY',
    true,
    '{
        "resourceId": "6c4ebdc2-c97a-4d2c-b43e-92324b1525c1",
        "ckanBaseUrl": "https://www.donneesquebec.ca/recherche/api/3/action/",
        "batchSize": 100,
        "businessType": "Centre Local d''Emploi",
        "fieldMapping": {
            "businessName": "nom",
            "addressStreet": "adresse",
            "addressCity": "ville",
            "phone": "telephone",
            "externalId": "_id"
        }
    }',
    CURRENT_TIMESTAMP
);

-- 17. Chambres de commerce
INSERT INTO data_sources (id, source_name, source_type, source_url, sync_frequency, active, config, created_at)
VALUES (
    gen_random_uuid(),
    'Données Québec - Chambres de Commerce',
    'CKAN_API',
    'https://www.donneesquebec.ca/recherche/dataset/chambres-commerce',
    'MONTHLY',
    true,
    '{
        "resourceId": "1c50df06-e8d4-43c7-bb8f-31779042f2f6",
        "ckanBaseUrl": "https://www.donneesquebec.ca/recherche/api/3/action/",
        "batchSize": 100,
        "businessType": "Chambre de Commerce",
        "fieldMapping": {
            "businessName": "nom",
            "addressStreet": "adresse",
            "addressCity": "ville",
            "phone": "telephone",
            "email": "courriel",
            "website": "site_web",
            "externalId": "_id"
        }
    }',
    CURRENT_TIMESTAMP
);

-- 18. Transporteurs scolaires
INSERT INTO data_sources (id, source_name, source_type, source_url, sync_frequency, active, config, created_at)
VALUES (
    gen_random_uuid(),
    'Données Québec - Transporteurs Scolaires',
    'CKAN_API',
    'https://www.donneesquebec.ca/recherche/dataset/transporteurs-scolaires',
    'MONTHLY',
    true,
    '{
        "resourceId": "8bb0ea6f-95ca-4955-8d76-41f7a744a40c",
        "ckanBaseUrl": "https://www.donneesquebec.ca/recherche/api/3/action/",
        "batchSize": 100,
        "businessType": "Transporteur Scolaire",
        "fieldMapping": {
            "businessName": "nom",
            "addressStreet": "adresse",
            "addressCity": "ville",
            "phone": "telephone",
            "externalId": "_id"
        }
    }',
    CURRENT_TIMESTAMP
);

-- 19. Cliniques dentaires
INSERT INTO data_sources (id, source_name, source_type, source_url, sync_frequency, active, config, created_at)
VALUES (
    gen_random_uuid(),
    'Données Québec - Cliniques Dentaires',
    'CKAN_API',
    'https://www.donneesquebec.ca/recherche/dataset/clinique-dentaires',
    'MONTHLY',
    true,
    '{
        "resourceId": "5370e3b3-3d42-4f33-9e1e-973d62b38c33",
        "ckanBaseUrl": "https://www.donneesquebec.ca/recherche/api/3/action/",
        "batchSize": 100,
        "businessType": "Clinique Dentaire",
        "fieldMapping": {
            "businessName": "nom",
            "addressStreet": "adresse",
            "addressCity": "ville",
            "phone": "telephone",
            "externalId": "_id"
        }
    }',
    CURRENT_TIMESTAMP
);

-- 20. Bénéficiaires CALQ (Entreprises culturelles)
INSERT INTO data_sources (id, source_name, source_type, source_url, sync_frequency, active, config, created_at)
VALUES (
    gen_random_uuid(),
    'Données Québec - Entreprises Culturelles',
    'CKAN_API',
    'https://www.donneesquebec.ca/recherche/dataset/beneficiaires-calq',
    'MONTHLY',
    true,
    '{
        "resourceId": "a62f4a40-7d88-4a6c-8ba0-c0235b6dcec5",
        "ckanBaseUrl": "https://www.donneesquebec.ca/recherche/api/3/action/",
        "batchSize": 100,
        "businessType": "Entreprise Culturelle",
        "fieldMapping": {
            "businessName": "nom",
            "addressStreet": "adresse",
            "addressCity": "ville",
            "externalId": "_id"
        }
    }',
    CURRENT_TIMESTAMP
);
