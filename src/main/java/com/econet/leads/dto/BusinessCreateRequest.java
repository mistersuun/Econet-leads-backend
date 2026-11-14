package com.econet.leads.dto;

import com.econet.leads.model.LeadStatus;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Data
public class BusinessCreateRequest {

    @NotBlank(message = "Le nom de l'entreprise est obligatoire")
    @Size(max = 255, message = "Le nom de l'entreprise ne doit pas dépasser 255 caractères")
    private String businessName;

    /** Optional: defaults to "Autre". */
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

    /** Ignored: the server always records manual leads with dataSource "Manuel". */
    @Size(max = 100, message = "La source des données ne doit pas dépasser 100 caractères")
    private String dataSource;

    @Size(max = 2000, message = "L'URL source ne doit pas dépasser 2000 caractères")
    private String sourceUrl;

    @Size(max = 255, message = "L'identifiant externe ne doit pas dépasser 255 caractères")
    private String externalId;

    /** Free text origin of the lead: "référence", "salon", "vu en passant", "site web"... */
    @Size(max = 200, message = "La source du lead ne doit pas dépasser 200 caractères")
    private String leadSource;

    /** Written as a NOTE contact on the new lead. */
    @Size(max = 5000, message = "Les notes ne doit pas dépasser 5000 caractères")
    private String notes;

    /** Partial Qualification (see QualificationService); validated like PATCH /qualification. */
    private Map<String, Object> qualification;

    /** Create even when an exact duplicate exists (otherwise 409 {error, duplicate}). */
    private Boolean force;

    /** NEW (default), CONTACTED, INTERESTED or QUOTE_SENT; terminal statuses are refused (400). */
    private LeadStatus leadStatus;
}
