package com.econet.leads.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class BusinessDTO {
    private UUID id;
    private String businessName;
    private String businessType;
    private UUID categoryId;
    private String addressStreet;
    private String addressCity;
    private String addressProvince;
    private String postalCode;
    private String phone;
    private String email;
    private String website;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private String dataSource;
    private String sourceUrl;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastVerified;
    private Integer dataQualityScore;
    private String fullAddress;
}
