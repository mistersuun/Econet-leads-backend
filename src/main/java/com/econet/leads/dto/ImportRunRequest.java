package com.econet.leads.dto;

import lombok.Data;

import java.util.Map;

/** Body of POST /api/imports/{importId}/run. */
@Data
public class ImportRunRequest {

    /** field (businessName, phone, email, website, addressStreet, addressCity, postalCode, businessType, notes, decisionMaker) -> column name. */
    private Map<String, String> mapping;

    private Defaults defaults;

    /** true: rows that match an existing lead are skipped; false (default): they update it. */
    private Boolean skipDuplicates;

    /** true: new leads are assigned to the caller. */
    private Boolean assignToMe;

    @Data
    public static class Defaults {
        private String businessType;
        private String addressCity;
        /** Required: the data source is named "Import – <sourceName>". */
        private String sourceName;
    }
}
