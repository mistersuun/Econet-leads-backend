package com.econet.leads.config;

import com.econet.leads.model.User;
import com.econet.leads.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DataInitializerTest {

    private final UserRepository repo = mock(UserRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);

    private User admin(String hash) {
        User u = new User();
        u.setUsername("admin");
        u.setRole(User.UserRole.ADMIN);
        u.setActive(true);
        u.setPasswordHash(hash);
        return u;
    }

    @Test
    void existingAdminPasswordIsNeverOverwritten() {
        User existing = admin(encoder.encode("my-own-password"));
        when(repo.findByUsername("admin")).thenReturn(Optional.of(existing));
        when(repo.existsByRoleAndActiveTrue(User.UserRole.ADMIN)).thenReturn(true);

        new DataInitializer(repo, encoder, "something-else").run();

        verify(repo, never()).save(any());
    }

    @Test
    void seedPasswordIsReplacedWithConfiguredPassword() {
        User seeded = admin(DataInitializer.V2_SEED_HASH);
        when(repo.findByUsername("admin")).thenReturn(Optional.of(seeded));

        new DataInitializer(repo, encoder, "Configured-Pass-1").run();

        verify(repo).save(seeded);
        assertThat(encoder.matches("Configured-Pass-1", seeded.getPasswordHash())).isTrue();
        assertThat(encoder.matches("admin123", seeded.getPasswordHash())).isFalse();
    }

    @Test
    void createsAdminWhenNoneExists() {
        when(repo.findByUsername("admin")).thenReturn(Optional.empty());
        when(repo.existsByRoleAndActiveTrue(User.UserRole.ADMIN)).thenReturn(false);
        when(repo.existsByUsername("admin")).thenReturn(false);

        new DataInitializer(repo, encoder, "Configured-Pass-1").run();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(User.UserRole.ADMIN);
        assertThat(encoder.matches("Configured-Pass-1", captor.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void generatesRandomPasswordWhenNotConfigured() {
        when(repo.findByUsername("admin")).thenReturn(Optional.empty());
        when(repo.existsByRoleAndActiveTrue(User.UserRole.ADMIN)).thenReturn(false);
        when(repo.existsByUsername("admin")).thenReturn(false);

        new DataInitializer(repo, encoder, "").run();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(repo).save(captor.capture());
        assertThat(encoder.matches("admin123", captor.getValue().getPasswordHash())).isFalse();
        assertThat(captor.getValue().getPasswordHash()).isNotBlank();
    }
}
