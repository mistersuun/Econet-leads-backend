package com.econet.leads.integration;

/**
 * Thrown when an import is requested for a source that already has a PENDING/RUNNING job.
 */
public class ImportAlreadyRunningException extends RuntimeException {

    public ImportAlreadyRunningException(String sourceName) {
        super("Une importation est déjà en cours pour la source : " + sourceName + ". Réessayez dans un instant.");
    }
}
