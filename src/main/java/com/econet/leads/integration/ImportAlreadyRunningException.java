package com.econet.leads.integration;

/**
 * Thrown when an import is requested for a source that already has a PENDING/RUNNING job.
 */
public class ImportAlreadyRunningException extends RuntimeException {

    public ImportAlreadyRunningException(String sourceName) {
        super("An import is already running for data source: " + sourceName);
    }
}
