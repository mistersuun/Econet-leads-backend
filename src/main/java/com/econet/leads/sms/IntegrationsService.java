package com.econet.leads.sms;

import com.econet.leads.config.MailSettings;
import com.econet.leads.integration.website.WebsiteDb;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Which optional providers are configured. Reads the property values directly, so it does not depend
 * on the beans of the features it reports on (Places usage tolerates a missing places_usage table).
 */
@Service
@Slf4j
public class IntegrationsService {

    private final MailSettings mail;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final SmsSettings sms;
    private final AlertSettings alerts;
    private final WebsiteDb websiteDb;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String placesKey;
    private final int placesLimit;
    private final String reviewUrl;
    private final String publicUrl;
    private final String crmUrl;

    public IntegrationsService(MailSettings mail, ObjectProvider<JavaMailSender> mailSender, SmsSettings sms,
                               AlertSettings alerts, WebsiteDb websiteDb, JdbcTemplate jdbc, Clock clock,
                               @Value("${app.places.api-key:}") String placesKey,
                               @Value("${app.places.daily-limit:100}") int placesLimit,
                               @Value("${app.reviews.google-url:}") String reviewUrl,
                               @Value("${app.public-url:}") String publicUrl,
                               @Value("${app.crm-url:http://localhost:5173}") String crmUrl) {
        this.mail = mail;
        this.mailSender = mailSender;
        this.sms = sms;
        this.alerts = alerts;
        this.websiteDb = websiteDb;
        this.jdbc = jdbc;
        this.clock = clock;
        this.placesKey = placesKey;
        this.placesLimit = placesLimit;
        this.reviewUrl = reviewUrl;
        this.publicUrl = publicUrl;
        this.crmUrl = crmUrl;
    }

    public SmsDTOs.IntegrationsStatus status() {
        boolean places = placesKey != null && !placesKey.isBlank();
        return new SmsDTOs.IntegrationsStatus(
                mail.isConfigured() && mailSender.getIfAvailable() != null,
                sms.isConfigured(),
                !alerts.phones().isEmpty(),
                !alerts.emails().isEmpty(),
                places,
                places ? placesUsedToday() : 0,
                placesLimit,
                reviewUrl != null && !reviewUrl.isBlank(),
                websiteDb.isConfigured(),
                resolvedPublicUrl(),
                crmUrl == null ? "" : crmUrl.replaceAll("/+$", ""));
    }

    private int placesUsedToday() {
        try {
            Integer calls = jdbc.query("SELECT calls FROM places_usage WHERE day = ?",
                    rs -> rs.next() ? rs.getInt(1) : 0, java.sql.Date.valueOf(LocalDate.now(clock)));
            return calls == null ? 0 : calls;
        } catch (RuntimeException e) {
            return 0; // table not created yet (another feature's migration) or unreadable
        }
    }

    private String resolvedPublicUrl() {
        if (publicUrl != null && !publicUrl.isBlank()) {
            return publicUrl.strip().replaceAll("/+$", "");
        }
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes) {
            try {
                return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString().replaceAll("/+$", "");
            } catch (RuntimeException ignored) {
                // fall through
            }
        }
        return "http://localhost:8080";
    }
}
