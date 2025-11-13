package com.econet.leads.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class BusinessUpdateRequest {

    @Size(max = 255, message = "Business name must not exceed 255 characters")
    private String businessName;

    @Size(max = 100, message = "Business type must not exceed 100 characters")
    private String businessType;

    private UUID categoryId;

    @Size(max = 255, message = "Address street must not exceed 255 characters")
    private String addressStreet;

    @Size(max = 100, message = "City must not exceed 100 characters")
    private String addressCity;

    @Size(max = 50, message = "Province must not exceed 50 characters")
    private String addressProvince;

    @Pattern(regexp = "^[A-Za-z]\\d[A-Za-z]\\s?\\d[A-Za-z]\\d$",
             message = "Postal code must be in format A1A 1A1 or a1a 1a1")
    private String postalCode;

    @Size(max = 20, message = "Phone must not exceed 20 characters")
    private String phone;

    @Email(message = "Email must be valid")
    @Size(max = 255, message = "Email must not exceed 255 characters")
    private String email;

    @Size(max = 500, message = "Website must not exceed 500 characters")
    private String website;

    @DecimalMin(value = "-90.0", message = "Latitude must be between -90 and 90")
    @DecimalMax(value = "90.0", message = "Latitude must be between -90 and 90")
    private BigDecimal latitude;

    @DecimalMin(value = "-180.0", message = "Longitude must be between -180 and 180")
    @DecimalMax(value = "180.0", message = "Longitude must be between -180 and 180")
    private BigDecimal longitude;
}
