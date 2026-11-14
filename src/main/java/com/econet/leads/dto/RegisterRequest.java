package com.econet.leads.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.econet.leads.model.User;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(message = "Le nom d'utilisateur est obligatoire")
    @Size(min = 3, max = 100, message = "Le nom d'utilisateur doit contenir entre 3 et 100 caractères")
    private String username;

    @NotBlank(message = "Le courriel est obligatoire")
    @Email(message = "Le courriel n'est pas valide")
    private String email;

    @NotBlank(message = "Le mot de passe est obligatoire")
    @Size(min = 6, message = "Le mot de passe doit contenir au moins 6 caractères")
    private String password;

    /** Optional role for the new account (ADMIN, USER or VIEWER); defaults to USER. */
    private User.UserRole role;
}
