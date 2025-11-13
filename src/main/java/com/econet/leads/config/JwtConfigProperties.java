package com.econet.leads.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * JWT configuration properties with validation.
 * Ensures JWT secrets are properly configured via environment variables.
 *
 * Required environment variables:
 * - JWT_SECRET: At least 32 characters (256 bits minimum for HS256)
 * - JWT_REFRESH_SECRET: At least 32 characters
 */
@Configuration
@ConfigurationProperties(prefix = "app.jwt")
@Validated
@Data
public class JwtConfigProperties {

    /**
     * JWT secret key for signing access tokens.
     * Must be at least 32 characters (256 bits) for HS256 algorithm.
     * MUST be set via JWT_SECRET environment variable.
     */
    @NotBlank(message = "JWT secret must be configured via JWT_SECRET environment variable")
    @Size(min = 32, message = "JWT secret must be at least 32 characters (256 bits) for security")
    private String secret;

    /**
     * Access token expiration time in milliseconds.
     */
    @Min(value = 60000, message = "JWT expiration must be at least 60 seconds")
    private long expirationMs;

    /**
     * JWT secret key for signing refresh tokens.
     * Must be at least 32 characters (256 bits) for HS256 algorithm.
     * MUST be set via JWT_REFRESH_SECRET environment variable.
     */
    @NotBlank(message = "JWT refresh secret must be configured via JWT_REFRESH_SECRET environment variable")
    @Size(min = 32, message = "JWT refresh secret must be at least 32 characters (256 bits) for security")
    private String refreshSecret;

    /**
     * Refresh token expiration time in milliseconds.
     */
    @Min(value = 60000, message = "JWT refresh expiration must be at least 60 seconds")
    private long refreshExpirationMs;
}
