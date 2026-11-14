package com.econet.leads.sms;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * app.sms.* (env TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, TWILIO_FROM). Configured only when all three
 * are set. No toString on purpose: the token must never reach a log line.
 */
@Component
@ConfigurationProperties(prefix = "app.sms")
@Getter
@Setter
public class SmsSettings {
    private String accountSid = "";
    private String authToken = "";
    private String from = "";
    /** Twilio REST base URL; only overridden by tests. */
    private String apiBase = "https://api.twilio.com";
    private int followUpLimitPerHour = 20;

    public boolean isConfigured() {
        return notBlank(accountSid) && notBlank(authToken) && notBlank(from);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
