package com.econet.leads.config;

import com.econet.leads.model.User;
import com.econet.leads.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * Bootstraps the first administrator account.
 *
 * - If an active ADMIN exists, nothing is changed; an existing password is never overwritten.
 *   The single exception is the hash hard-coded in migration V2 (published in the repository, and
 *   not even matching the "admin123" its comment claims), which is treated as "no password set
 *   yet" and replaced.
 * - Otherwise the "admin" user is created (or its seed password replaced) with the password from
 *   app.admin.initial-password (env ADMIN_INITIAL_PASSWORD). If that is empty a random password is
 *   generated and logged once at WARN level. The local profile defaults it to "admin123".
 */
@Component
@Order(0)
@Slf4j
public class DataInitializer implements CommandLineRunner {

    static final String ADMIN_USERNAME = "admin";
    static final String ADMIN_EMAIL = "admin@econet-leads.com";
    /** password_hash inserted by V2__seed_data.sql; public, so it must not survive in a real deployment */
    static final String V2_SEED_HASH = "$2a$10$8EhPYz5hVj7y6OE4s4T4mey4yLqG.jZh5NM7cJKBq5VpYvO2gJCw.";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String initialPassword;

    public DataInitializer(UserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           @Value("${app.admin.initial-password:}") String initialPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.initialPassword = initialPassword;
    }

    @Override
    public void run(String... args) {
        Optional<User> seededAdmin = userRepository.findByUsername(ADMIN_USERNAME)
                .filter(u -> V2_SEED_HASH.equals(u.getPasswordHash()));
        if (seededAdmin.isPresent()) {
            User admin = seededAdmin.get();
            admin.setPasswordHash(passwordEncoder.encode(resolvePassword("replaced the V2 seed password of 'admin'")));
            userRepository.save(admin);
            log.info("Replaced the well-known seed password of the '{}' account", ADMIN_USERNAME);
            return;
        }

        if (userRepository.existsByRoleAndActiveTrue(User.UserRole.ADMIN)) {
            log.debug("An admin account exists; leaving it unchanged");
            return;
        }

        if (userRepository.existsByUsername(ADMIN_USERNAME)) {
            log.warn("No active ADMIN account exists and the username '{}' is taken by a non-admin account; "
                    + "not creating a bootstrap admin. Promote a user to ADMIN in the database.", ADMIN_USERNAME);
            return;
        }

        User admin = new User();
        admin.setUsername(ADMIN_USERNAME);
        admin.setEmail(ADMIN_EMAIL);
        admin.setPasswordHash(passwordEncoder.encode(resolvePassword("created the bootstrap admin account 'admin'")));
        admin.setRole(User.UserRole.ADMIN);
        admin.setActive(true);
        userRepository.save(admin);
        log.info("Created bootstrap admin account '{}'", ADMIN_USERNAME);
    }

    private String resolvePassword(String action) {
        if (initialPassword != null && !initialPassword.isBlank()) {
            return initialPassword;
        }
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        // Logged exactly once, when the password is set; it is never logged again.
        log.warn("ADMIN_INITIAL_PASSWORD is not set: {} with generated password: {} "
                + "(change it after first login; this message is not repeated)", action, generated);
        return generated;
    }
}
