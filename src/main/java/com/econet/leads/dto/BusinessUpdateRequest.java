package com.econet.leads.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class BusinessUpdateRequest {

    @Size(max = 255, message = "Le nom de l'entreprise ne doit pas dépasser 255 caractères")
    private String businessName;

    @Size(max = 100, message = "Le type d'entreprise ne doit pas dépasser 100 caractères")
    private String businessType;

    private UUID categoryId;

    @Size(max = 255, message = "L'adresse ne doit pas dépasser 255 caractères")
    private String addressStreet;

    @Size(max = 100, message = "La ville ne doit pas dépasser 100 caractères")
    private String addressCity;

    @Size(max = 50, message = "La province ne doit pas dépasser 50 caractères")
    private String addressProvince;

    @Pattern(regexp = "^[A-Za-z]\\d[A-Za-z]\\s?\\d[A-Za-z]\\d$",
             message = "Le code postal doit avoir le format A1A 1A1")
    private String postalCode;

    @Size(max = 20, message = "Le téléphone ne doit pas dépasser 20 caractères")
    private String phone;

    @Email(message = "Le courriel n'est pas valide")
    @Size(max = 255, message = "Le courriel ne doit pas dépasser 255 caractères")
    private String email;

    @Size(max = 500, message = "Le site web ne doit pas dépasser 500 caractères")
    private String website;

    @DecimalMin(value = "-90.0", message = "La latitude doit être entre -90 et 90")
    @DecimalMax(value = "90.0", message = "La latitude doit être entre -90 et 90")
    private BigDecimal latitude;

    @DecimalMin(value = "-180.0", message = "La longitude doit être entre -180 et 180")
    @DecimalMax(value = "180.0", message = "La longitude doit être entre -180 et 180")
    private BigDecimal longitude;

    @DecimalMin(value = "0.0", message = "La valeur estimée doit être positive")
    @Digits(integer = 8, fraction = 2, message = "La valeur estimée doit avoir au plus 8 chiffres et 2 décimales")
    private BigDecimal estimatedValue;

    /** Assign the lead to this user (null = unchanged) */
    private UUID assignedToId;
}
