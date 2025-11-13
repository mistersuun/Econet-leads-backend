package com.econet.leads.config;

import com.econet.leads.model.User;
import com.econet.leads.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Data initializer that ensures the admin user exists with the correct password.
 * This runs after Flyway migrations to fix any password hash issues.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        ensureAdminUserExists();
    }

    private void ensureAdminUserExists() {
        Optional<User> adminUser = userRepository.findByUsername("admin");
        
        if (adminUser.isPresent()) {
            User admin = adminUser.get();
            // Verify the password hash is correct by checking if it matches "admin123"
            // If not, update it
            if (!passwordEncoder.matches("admin123", admin.getPasswordHash())) {
                log.warn("Admin user password hash is incorrect. Updating...");
                admin.setPasswordHash(passwordEncoder.encode("admin123"));
                userRepository.save(admin);
                log.info("Admin user password has been updated.");
            } else {
                log.debug("Admin user exists with correct password.");
            }
        } else {
            log.info("Creating default admin user...");
            User admin = new User();
            admin.setUsername("admin");
            admin.setEmail("admin@econet-leads.com");
            admin.setPasswordHash(passwordEncoder.encode("admin123"));
            admin.setRole(User.UserRole.ADMIN);
            admin.setActive(true);
            userRepository.save(admin);
            log.info("Default admin user created. Username: admin, Password: admin123");
        }
    }
}


