-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Users table
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    username VARCHAR(100) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login TIMESTAMP,
    active BOOLEAN NOT NULL DEFAULT true
);

-- Business categories table
CREATE TABLE business_categories (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    category_name VARCHAR(100) NOT NULL UNIQUE,
    parent_category_id UUID REFERENCES business_categories(id),
    description TEXT,
    keywords TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Businesses table (main prospects/businesses)
CREATE TABLE businesses (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_name VARCHAR(255) NOT NULL,
    business_type VARCHAR(100) NOT NULL,
    category_id UUID REFERENCES business_categories(id),
    address_street VARCHAR(255),
    address_city VARCHAR(100),
    address_province VARCHAR(50) DEFAULT 'QC',
    postal_code VARCHAR(10),
    phone VARCHAR(20),
    email VARCHAR(255),
    website VARCHAR(500),
    latitude DECIMAL(10, 8),
    longitude DECIMAL(11, 8),
    data_source VARCHAR(100) NOT NULL,
    source_url TEXT,
    external_id VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_verified TIMESTAMP,
    data_quality_score INT DEFAULT 0,
    CONSTRAINT ck_quality_score CHECK (data_quality_score BETWEEN 0 AND 100)
);

-- Contacts table (CRM tracking)
CREATE TABLE contacts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_id UUID NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    contact_date TIMESTAMP NOT NULL,
    contact_type VARCHAR(20) NOT NULL,
    contact_status VARCHAR(50) NOT NULL,
    contact_person VARCHAR(255),
    notes TEXT,
    next_action TEXT,
    next_action_date TIMESTAMP,
    user_id UUID NOT NULL REFERENCES users(id),
    outcome VARCHAR(50),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Data sources table
CREATE TABLE data_sources (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    source_name VARCHAR(100) NOT NULL UNIQUE,
    source_type VARCHAR(50) NOT NULL,
    source_url TEXT,
    last_sync TIMESTAMP,
    sync_frequency VARCHAR(50),
    active BOOLEAN NOT NULL DEFAULT true,
    config JSONB,
    records_count INT DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Scraper jobs table
CREATE TABLE scraper_jobs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    source_id UUID REFERENCES data_sources(id),
    job_type VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    records_processed INT DEFAULT 0,
    records_added INT DEFAULT 0,
    records_updated INT DEFAULT 0,
    errors TEXT,
    log TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Contracts table (Pages Jaunes commercial agreement tracking)
CREATE TABLE contracts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_id UUID NOT NULL REFERENCES businesses(id),
    lead_source VARCHAR(100) NOT NULL,
    contract_date DATE NOT NULL,
    contract_value DECIMAL(10, 2),
    compensation_owed DECIMAL(10, 2),
    compensation_paid BOOLEAN DEFAULT false,
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes for performance
CREATE INDEX idx_businesses_type ON businesses(business_type);
CREATE INDEX idx_businesses_city ON businesses(address_city);
CREATE INDEX idx_businesses_postal_code ON businesses(postal_code);
CREATE INDEX idx_businesses_data_source ON businesses(data_source);
CREATE INDEX idx_businesses_updated_at ON businesses(updated_at DESC);
CREATE INDEX idx_businesses_created_at ON businesses(created_at DESC);
CREATE INDEX idx_businesses_quality_score ON businesses(data_quality_score);
CREATE INDEX idx_businesses_phone ON businesses(phone);

CREATE INDEX idx_contacts_business_id ON contacts(business_id);
CREATE INDEX idx_contacts_user_id ON contacts(user_id);
CREATE INDEX idx_contacts_next_action_date ON contacts(next_action_date);
CREATE INDEX idx_contacts_contact_date ON contacts(contact_date DESC);
CREATE INDEX idx_contacts_status ON contacts(contact_status);

CREATE INDEX idx_scraper_jobs_source_id ON scraper_jobs(source_id);
CREATE INDEX idx_scraper_jobs_status ON scraper_jobs(status);
CREATE INDEX idx_scraper_jobs_started_at ON scraper_jobs(started_at DESC);

CREATE INDEX idx_contracts_business_id ON contracts(business_id);
CREATE INDEX idx_contracts_lead_source ON contracts(lead_source);
CREATE INDEX idx_contracts_contract_date ON contracts(contract_date DESC);

-- Full-text search index on business name
CREATE INDEX idx_businesses_name_gin ON businesses USING gin(to_tsvector('french', business_name));

-- Update trigger for updated_at columns
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_businesses_updated_at BEFORE UPDATE ON businesses
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_contacts_updated_at BEFORE UPDATE ON contacts
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_data_sources_updated_at BEFORE UPDATE ON data_sources
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_contracts_updated_at BEFORE UPDATE ON contracts
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
