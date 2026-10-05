package com.econet.leads.integration;

import com.econet.leads.model.Business;

import java.util.List;

/**
 * Fetches and maps the raw records of one data source into (unsaved) Business candidates.
 * Persisting them, duplicate detection and job bookkeeping are handled by {@link ImportJobRunner}.
 */
@FunctionalInterface
public interface BusinessRecordFetcher {

    List<Business> fetch() throws Exception;
}
