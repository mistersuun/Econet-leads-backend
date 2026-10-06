-- V8: new lead sources (Montréal permits, Registre des entreprises), public tenders, phone enrichment
--
-- Every external field name, URL and filter of the new importers lives in data_sources.config so a
-- format change on the publisher side can be fixed with an UPDATE instead of a deploy. Importers
-- fail the job with the list of missing columns/files when the format no longer matches.

-- 1. Source-specific facts shown to the caller (BusinessDTO.sourceDetails)
ALTER TABLE businesses ADD COLUMN source_details JSONB;
COMMENT ON COLUMN businesses.source_details IS 'Short source-specific facts for the caller, e.g. {"NEQ": "...", "Secteur": "..."}';

-- "À enrichir" list / dashboard toEnrich: open leads without a phone
CREATE INDEX idx_businesses_to_enrich ON businesses(created_at)
    WHERE phone IS NULL AND lead_status NOT IN ('WON', 'LOST', 'DO_NOT_CALL');

-- 2. Public tenders (appels d'offres) — contracts to bid on, not people to call
CREATE TABLE tenders (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source            VARCHAR(20)  NOT NULL,
    external_id       VARCHAR(255) NOT NULL,
    title             TEXT         NOT NULL,
    description       TEXT,
    buyer             VARCHAR(500),
    region            VARCHAR(500),
    category          VARCHAR(255),
    published_at      TIMESTAMP,
    closing_at        TIMESTAMP,
    url               TEXT         NOT NULL,
    estimated_value   DECIMAL(15, 2),
    matched_keywords  JSONB,
    notice_type       VARCHAR(20),
    awarded_to        VARCHAR(500),
    contract_end_at   TIMESTAMP,
    source_release_at TIMESTAMP,
    status            VARCHAR(20)  NOT NULL DEFAULT 'NEW',
    notes             TEXT,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_tenders_source_external_id UNIQUE (source, external_id),
    CONSTRAINT ck_tenders_source CHECK (source IN ('CANADABUYS', 'SEAO')),
    CONSTRAINT ck_tenders_status CHECK (status IN ('NEW', 'REVIEWING', 'BIDDING', 'SUBMITTED', 'WON', 'LOST', 'IGNORED')),
    CONSTRAINT ck_tenders_notice_type CHECK (notice_type IS NULL OR notice_type IN ('TENDER', 'AWARD'))
);

CREATE INDEX idx_tenders_status ON tenders(status);
CREATE INDEX idx_tenders_closing_at ON tenders(closing_at);
CREATE INDEX idx_tenders_published_at ON tenders(published_at);

CREATE TRIGGER update_tenders_updated_at BEFORE UPDATE ON tenders
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

COMMENT ON COLUMN tenders.external_id IS 'CanadaBuys solicitation number / SEAO OCID';
COMMENT ON COLUMN tenders.status IS 'Team follow-up (TenderStatus); never changed by imports';
COMMENT ON COLUMN tenders.contract_end_at IS 'End of an awarded contract (SEAO awards): approach the buyer before renewal';

-- 3. Data sources

-- 3a. Montréal construction/transformation permits (CKAN datastore on donnees.montreal.ca)
-- Columns per the 2026-09 file: no_demande, id_permis, date_debut, date_emission, emplacement,
-- arrondissement, code_type_base_demande, description_type_demande, description_type_batiment,
-- description_categorie_batiment, nature_travaux, nb_logements, longitude, latitude, loc_x, loc_y.
-- No estimated-cost column is published at the moment: minEstimatedCost applies only if the
-- "estimatedCost" column appears; otherwise the fallback (>= 4 dwellings or non-residential) is used.
INSERT INTO data_sources (id, source_name, source_type, source_url, sync_frequency, active, config, created_at)
VALUES (
    gen_random_uuid(),
    'Montréal - Permis de construction',
    'CKAN_API',
    'https://donnees.montreal.ca/dataset/permis-construction',
    'WEEKLY',
    true,
    '{
        "importer": "MONTREAL_PERMITS",
        "ckanBaseUrl": "https://donnees.montreal.ca/api/3/action/",
        "resourceId": "5232a72d-235a-48eb-ae20-bb9d501300ad",
        "batchSize": 1000,
        "lookbackDays": 120,
        "minEstimatedCost": 50000,
        "permitTypeKeywords": ["construction", "transformation"],
        "residentialKeywords": ["residentiel", "habitation", "logement", "unifamilial", "duplex", "triplex", "condo"],
        "fallbackMinDwellingUnits": 4,
        "businessType": "Chantier",
        "city": "Montréal",
        "namePrefix": "Chantier – ",
        "fieldMapping": {
            "permitNumber": "no_demande",
            "permitId": "id_permis",
            "issueDate": "date_emission",
            "address": "emplacement",
            "borough": "arrondissement",
            "permitTypeCode": "code_type_base_demande",
            "permitType": "description_type_demande",
            "buildingType": "description_type_batiment",
            "buildingCategory": "description_categorie_batiment",
            "workDescription": "nature_travaux",
            "dwellingUnits": "nb_logements",
            "estimatedCost": "cout_travaux_estimes",
            "latitude": "latitude",
            "longitude": "longitude"
        },
        "requiredFields": ["permitNumber", "issueDate", "address", "permitType"]
    }',
    CURRENT_TIMESTAMP
);

-- 3b. Registre des entreprises du Québec (ZIP of 6 CSV files joined by NEQ, ~225 MB).
-- Inactive until an admin sets it up (upload the ZIP, or activate to download it).
-- License of the dataset: CC BY-NC-SA 4.0 (non-commercial) — check before prospecting with it.
INSERT INTO data_sources (id, source_name, source_type, source_url, sync_frequency, active, config, created_at)
VALUES (
    gen_random_uuid(),
    'Registre des entreprises du Québec',
    'BULK_FILE',
    'https://www.donneesquebec.ca/recherche/dataset/registre-des-entreprises',
    'MONTHLY',
    false,
    '{
        "importer": "QUEBEC_BUSINESS_REGISTER",
        "downloadUrl": null,
        "ckanBaseUrl": "https://www.donneesquebec.ca/recherche/api/3/action/",
        "resourceId": "eac1b5f1-d8c0-4690-9c51-316d44ed9d94",
        "charset": "UTF-8",
        "separator": ",",
        "files": {
            "enterprises": "Entreprise",
            "establishments": "Etablissement",
            "names": "Nom",
            "domainValues": "DomaineValeur"
        },
        "columns": {
            "establishments": {
                "neq": "NEQ", "principal": "IND_ETAB_PRINC",
                "line1": "LIGN1_ADR", "line2": "LIGN2_ADR", "line3": "LIGN3_ADR", "line4": "LIGN4_ADR",
                "activityCode": "COD_ACT_ECON", "activityDescription": "DESC_ACT_ECON_ETAB",
                "activityCode2": "COD_ACT_ECON2", "activityDescription2": "DESC_ACT_ECON_ETAB2",
                "name": "NOM_ETAB"
            },
            "enterprises": {
                "neq": "NEQ", "status": "COD_STAT_IMMAT", "employees": "COD_INTVAL_EMPLO_QUE",
                "activityCode": "COD_ACT_ECON_CAE", "activityDescription": "DESC_ACT_ECON_ASSUJ"
            },
            "names": {
                "neq": "NEQ", "name": "NOM_ASSUJ", "status": "STAT_NOM", "type": "TYP_NOM_ASSUJ", "endDate": "DAT_FIN_NOM_ASSUJ"
            },
            "domainValues": { "type": "TYP_DOM_VAL", "code": "COD_DOM_VAL", "label": "VAL_DOM_FRAN" }
        },
        "activeStatusCodes": ["IM"],
        "currentNameStatuses": ["V"],
        "preferredNameTypes": ["N", "M", "A"],
        "employeeDomainType": "INTVAL_EMPLO_QUE",
        "cities": ["Montréal", "Laval", "Longueuil", "Brossard"],
        "cityAliases": {
            "Montréal": ["Saint-Laurent", "Anjou", "Lachine", "LaSalle", "Verdun", "Outremont", "Montréal-Nord",
                         "Saint-Léonard", "Pierrefonds", "Roxboro", "Rivière-des-Prairies", "Pointe-aux-Trembles",
                         "L''Île-Bizard", "Sainte-Geneviève", "Île-des-Soeurs"],
            "Laval": ["Chomedey", "Vimont", "Sainte-Rose", "Fabreville", "Duvernay", "Auteuil", "Laval-des-Rapides",
                      "Pont-Viau", "Sainte-Dorothée", "Laval-Ouest", "Saint-François", "Saint-Vincent-de-Paul"],
            "Longueuil": ["Saint-Hubert", "Greenfield Park", "LeMoyne"]
        },
        "sectors": [
            {"label": "Bureaux professionnels", "codes": [],
             "keywords": ["avocat", "notaire", "juridique", "comptab", "expert comptable", "architecte", "ingenieur",
                          "assurance", "courtier", "conseil en gestion", "services financiers", "agence de placement",
                          "law office", "accounting"]},
            {"label": "Cliniques médicales", "codes": [],
             "keywords": ["clinique", "medecin", "medical", "physiotherap", "chiropra", "optometr", "psycholog",
                          "laboratoire medical", "ergotherap", "pharmacie"]},
            {"label": "Dentistes", "codes": [],
             "keywords": ["dentist", "dentaire", "denturolog", "orthodont", "hygiene dentaire"]},
            {"label": "Garderies", "codes": [],
             "keywords": ["garderie", "centre de la petite enfance", "service de garde", "services de garde", "day care", "daycare"]},
            {"label": "Restaurants", "codes": [],
             "keywords": ["restaurant", "restauration", "traiteur", "brasserie", "bistro"]},
            {"label": "Centres sportifs", "codes": [],
             "keywords": ["conditionnement physique", "centre sportif", "centre de sport", "gymnase", "salle d entrainement",
                          "studio de yoga", "arts martiaux", "fitness", "gym"]},
            {"label": "Écoles", "codes": [],
             "keywords": ["ecole", "enseignement", "academie", "centre de formation", "college prive"]},
            {"label": "Gestion immobilière", "codes": [],
             "keywords": ["gestion immobiliere", "gestion de proprietes", "gestion d immeubles", "location d immeubles",
                          "exploitant d immeubles", "bailleur", "property management"]},
            {"label": "Syndicats de copropriété", "codes": [],
             "keywords": ["syndicat de copropriete", "copropriete"],
             "nameKeywords": ["syndicat de copropriete", "syndicat des coproprietaires", "copropriete"]}
        ]
    }',
    CURRENT_TIMESTAMP
);

-- 3c. CanadaBuys open tender notices (federal), daily CSV
INSERT INTO data_sources (id, source_name, source_type, source_url, sync_frequency, active, config, created_at)
VALUES (
    gen_random_uuid(),
    'CanadaBuys - Appels d''offres',
    'CSV_DOWNLOAD',
    'https://canadabuys.canada.ca/en/tender-opportunities',
    'DAILY',
    true,
    '{
        "importer": "CANADABUYS_TENDERS",
        "csvUrl": "https://canadabuys.canada.ca/opendata/pub/openTenderNotice-ouvertAvisAppelOffres.csv",
        "keywords": ["nettoyage", "entretien ménager", "conciergerie", "janitorial", "custodial", "cleaning", "housekeeping"],
        "regionKeywords": ["quebec", "canada"],
        "keepWhenRegionMissing": true,
        "excludedStatuses": ["cancelled", "annule", "expired", "expire"],
        "columns": {
            "referenceNumber": "referenceNumber-numeroReference",
            "solicitationNumber": "solicitationNumber-numeroSollicitation",
            "titleEn": "title-titre-eng",
            "titleFr": "title-titre-fra",
            "descriptionEn": "tenderDescription-descriptionAppelOffres-eng",
            "descriptionFr": "tenderDescription-descriptionAppelOffres-fra",
            "publishedAt": "publicationDate-datePublication",
            "closingAt": "tenderClosingDate-appelOffresDateCloture",
            "status": "tenderStatus-appelOffresStatut-eng",
            "category": "procurementCategory-categorieApprovisionnement",
            "noticeType": "noticeType-avisType-eng",
            "regionsOfDelivery": "regionsOfDelivery-regionsLivraison-eng",
            "regionsOfOpportunity": "regionsOfOpportunity-regionAppelOffres-eng",
            "buyerEn": "contractingEntityName-nomEntitContractante-eng",
            "buyerFr": "contractingEntityName-nomEntitContractante-fra",
            "urlEn": "noticeURL-URLavis-eng",
            "urlFr": "noticeURL-URLavis-fra",
            "unspscDescription": "unspscDescription-eng"
        },
        "requiredColumns": ["solicitationNumber", "titleEn", "closingAt", "urlEn", "regionsOfDelivery", "descriptionEn"]
    }',
    CURRENT_TIMESTAMP
);

-- 3d. SEAO (Quebec public bodies), OCDS JSON files on Données Québec
INSERT INTO data_sources (id, source_name, source_type, source_url, sync_frequency, active, config, created_at)
VALUES (
    gen_random_uuid(),
    'SEAO - Avis et contrats',
    'CKAN_API',
    'https://www.donneesquebec.ca/recherche/dataset/systeme-electronique-dappel-doffres-seao',
    'WEEKLY',
    true,
    '{
        "importer": "SEAO_TENDERS",
        "ckanBaseUrl": "https://www.donneesquebec.ca/recherche/api/3/action/",
        "packageId": "systeme-electronique-dappel-doffres-seao",
        "files": [
            {"prefix": "mensuel", "latest": 2},
            {"prefix": "hebdo", "latest": 4}
        ],
        "fileUrls": [],
        "keywords": ["nettoyage", "entretien ménager", "conciergerie", "janitorial", "custodial", "cleaning", "housekeeping"],
        "includeAwards": true,
        "noticeUrlTemplate": "https://seao.gouv.qc.ca/avis-du-jour"
    }',
    CURRENT_TIMESTAMP
);
