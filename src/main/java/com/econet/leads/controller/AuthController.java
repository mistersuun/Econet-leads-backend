package com.econet.leads.controller;

import com.econet.leads.dto.AuthResponse;
import com.econet.leads.dto.LoginRequest;
import com.econet.leads.dto.RefreshTokenRequest;
import com.econet.leads.dto.RegisterRequest;
import com.econet.leads.dto.UserInfoDTO;
import com.econet.leads.model.User;
import com.econet.leads.security.AuthenticationFacade;
import com.econet.leads.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Authentication endpoints")
public class AuthController {

    private final AuthService authService;
    private final AuthenticationFacade authenticationFacade;

    @PostMapping("/login")
    @Operation(summary = "Login user", description = "Authenticate user and return JWT tokens")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest loginRequest) {
        AuthResponse response = authService.login(loginRequest);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    @Operation(summary = "Current user", description = "Identity and role of the authenticated user")
    public ResponseEntity<UserInfoDTO> me() {
        User user = authenticationFacade.getCurrentUser();
        return ResponseEntity.ok(new UserInfoDTO(user.getId(), user.getUsername(), user.getEmail(), user.getRole().name()));
    }

    @PostMapping("/register")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Register user (admin only)", description = "Create a new user account. Restricted to administrators.")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest registerRequest) {
        AuthResponse response = authService.register(registerRequest);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh access token", description = "Get a new access token using refresh token")
    public ResponseEntity<AuthResponse> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        AuthResponse response = authService.refreshToken(request.getRefreshToken());
        return ResponseEntity.ok(response);
    }
}
