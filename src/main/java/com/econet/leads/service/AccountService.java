package com.econet.leads.service;

import com.econet.leads.exception.ApiException;
import com.econet.leads.model.User;
import com.econet.leads.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

/**
 * Password changes. Refresh tokens are stateless JWTs (nothing is stored server side), so they
 * cannot be revoked individually: access tokens expire after 24 h, refresh tokens after 30 days,
 * and rotating JWT_REFRESH_SECRET logs everyone out at once.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {

    public static final int MIN_PASSWORD_LENGTH = 10;
    public static final int MAX_PASSWORD_LENGTH = 128;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /** The caller changes their own password; the current one must match. */
    @Transactional
    public void changeOwnPassword(UUID userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Utilisateur introuvable : " + userId));
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("Le mot de passe actuel est incorrect");
        }
        validateNewPassword(user, newPassword);
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("Le nouveau mot de passe doit être différent de l'actuel");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        log.info("User {} changed their password", user.getUsername());
    }

    /** An administrator sets another user's password. */
    @Transactional
    public void resetPassword(UUID userId, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Utilisateur introuvable : " + userId));
        validateNewPassword(user, newPassword);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        log.info("Password of user {} was reset by an administrator", user.getUsername());
    }

    static void validateNewPassword(User user, String newPassword) {
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw ApiException.badRequest("Le mot de passe doit contenir au moins " + MIN_PASSWORD_LENGTH + " caractères");
        }
        if (newPassword.length() > MAX_PASSWORD_LENGTH) {
            throw ApiException.badRequest("Le mot de passe ne doit pas dépasser " + MAX_PASSWORD_LENGTH + " caractères");
        }
        String lower = newPassword.toLowerCase(Locale.ROOT);
        if (lower.equals(user.getUsername().toLowerCase(Locale.ROOT))) {
            throw ApiException.badRequest("Le mot de passe ne doit pas être identique au nom d'utilisateur");
        }
    }
}
