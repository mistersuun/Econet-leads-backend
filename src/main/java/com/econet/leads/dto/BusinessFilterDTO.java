package com.econet.leads.dto;

import lombok.Data;

@Data
public class BusinessFilterDTO {
    private String businessType;
    private String city;
    private String province;
    private String dataSource;
    private String searchTerm;
    private Integer minQualityScore;
    private Integer maxQualityScore;
    private Boolean stale;  // Filter for stale data
    private String contactStatus;  // Filter by last contact status
}
