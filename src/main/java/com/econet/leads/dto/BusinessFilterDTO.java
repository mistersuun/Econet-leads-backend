package com.econet.leads.dto;

import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * Query parameters of GET /api/businesses and GET /api/businesses/export.csv.
 */
@Data
public class BusinessFilterDTO {
    /** name / phone / city contains, case-insensitive */
    private String q;
    /** repeatable (?leadStatus=NEW&leadStatus=WON) or comma list (?leadStatus=NEW,WON) */
    private List<String> leadStatus;
    private String businessType;
    /** exact city, case-insensitive */
    private String city;
    private String province;
    private String dataSource;
    private Boolean hasPhone;
    /** user id, or "me" (resolved by the controller into assignedToUserId) */
    private String assignedTo;
    private Integer minQualityScore;
    private Integer maxQualityScore;
    /** nextFollowUpAt <= now */
    private Boolean followUpDue;

    // Legacy filters (kept for backward compatibility)
    private String searchTerm;  // name or street contains
    private Boolean stale;      // never verified or verified > 90 days ago
    private String contactStatus;  // status of the most recent contact

    /** Resolved value of assignedTo (not a request parameter). */
    private transient UUID assignedToUserId;
}
