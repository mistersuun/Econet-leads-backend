-- Insert default business categories
INSERT INTO business_categories (category_name, description, keywords) VALUES
('CPE', 'Centres de la petite enfance', 'garderie,daycare,enfance,petite'),
('Clinique', 'Cliniques médicales et centres de santé', 'clinique,medical,health,santé'),
('CHSLD', 'Centres d''hébergement et de soins de longue durée', 'chsld,senior,residence,aging'),
('Restaurant', 'Restaurants et services alimentaires', 'restaurant,food,cuisine,dining'),
('Résidence pour aînés', 'Résidences pour personnes âgées', 'residence,senior,retraite,retirement'),
('Commerce de détail', 'Magasins et boutiques', 'retail,store,boutique,magasin'),
('Services professionnels', 'Services professionnels divers', 'service,professional,consulting'),
('Immobilier', 'Agences et services immobiliers', 'real estate,immobilier,property'),
('Construction', 'Entreprises de construction et rénovation', 'construction,renovation,building'),
('Hôtel', 'Hôtels et hébergement', 'hotel,accommodation,hebergement');

-- Insert default data sources
INSERT INTO data_sources (source_name, source_type, source_url, sync_frequency, active, config) VALUES
(
    'Données Québec - CPE',
    'CKAN_API',
    'https://www.donneesquebec.ca/recherche/dataset/centres-petite-enfance',
    'WEEKLY',
    true,
    '{"resource_id": "to_be_configured", "api_base": "https://www.donneesquebec.ca/recherche/api/3/action/"}'::jsonb
),
(
    'Données Québec - CHSLD',
    'CKAN_API',
    'https://www.donneesquebec.ca/recherche/dataset/chsld',
    'WEEKLY',
    true,
    '{"resource_id": "to_be_configured", "api_base": "https://www.donneesquebec.ca/recherche/api/3/action/"}'::jsonb
),
(
    'Données Montréal - Restaurants',
    'CKAN_API',
    'https://donnees.montreal.ca/dataset/permis-restaurants',
    'WEEKLY',
    true,
    '{"resource_id": "to_be_configured", "api_base": "https://donnees.montreal.ca/api/3/action/"}'::jsonb
),
(
    'Statistics Canada - Healthcare Facilities',
    'CSV_DOWNLOAD',
    'https://www150.statcan.gc.ca/n1/en/catalogue/82-006-X',
    'MONTHLY',
    true,
    '{"csv_url": "to_be_configured", "province_filter": "QC"}'::jsonb
),
(
    'Pages Jaunes - Manual Scraping',
    'WEB_SCRAPER',
    'https://www.pagesjaunes.ca',
    'MANUAL',
    true,
    '{"rate_limit_ms": 3000, "max_pages": 10}'::jsonb
);

-- Create default admin user (password: admin123 - CHANGE IN PRODUCTION!)
-- Password hash generated with BCrypt for 'admin123'
-- Note: Password will be automatically corrected by DataInitializer on startup if incorrect
INSERT INTO users (username, email, password_hash, role, active) VALUES
('admin', 'admin@econet-leads.com', '$2a$10$8EhPYz5hVj7y6OE4s4T4mey4yLqG.jZh5NM7cJKBq5VpYvO2gJCw.', 'ADMIN', true);
