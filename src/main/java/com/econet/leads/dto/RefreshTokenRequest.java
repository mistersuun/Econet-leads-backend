package com.econet.leads.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RefreshTokenRequest {

    @NotBlank(message = "Le jeton de rafraîchissement est obligatoire")
    private String refreshToken;
}
