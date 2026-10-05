package com.econet.leads.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Startup must fail fast when JWT secrets are missing or shorter than 32 bytes. */
class JwtConfigPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
            .withUserConfiguration(JwtConfigProperties.class)
            .withPropertyValues("app.jwt.expiration-ms=86400000", "app.jwt.refresh-expiration-ms=2592000000");

    @Test
    void missingSecretsFailStartup() {
        runner.withPropertyValues("app.jwt.secret=", "app.jwt.refresh-secret=")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void shortSecretFailsStartup() {
        runner.withPropertyValues("app.jwt.secret=too-short", "app.jwt.refresh-secret=0123456789abcdef0123456789abcdef-r")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void validSecretsStart() {
        runner.withPropertyValues("app.jwt.secret=0123456789abcdef0123456789abcdef-a",
                        "app.jwt.refresh-secret=0123456789abcdef0123456789abcdef-r")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }
}
