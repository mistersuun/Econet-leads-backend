package com.econet.leads.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * app.mail.* (env SMTP_HOST, SMTP_PORT, SMTP_USERNAME, SMTP_PASSWORD, MAIL_FROM, MAIL_FROM_NAME).
 * SMTP is "configured" when host is set; from defaults to the username.
 */
@Component
@ConfigurationProperties(prefix = "app.mail")
@Data
public class MailSettings {
    private String host = "";
    private int port = 587;
    private String username = "";
    private String password = "";
    private String from = "";
    private String fromName = "EcoNet Propreté";
    private boolean starttls = true;
    private int rateLimitPerHour = 20;

    public boolean isConfigured() {
        return host != null && !host.isBlank();
    }

    public String effectiveFrom() {
        return from != null && !from.isBlank() ? from : username;
    }
}
