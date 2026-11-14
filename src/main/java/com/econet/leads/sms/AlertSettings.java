package com.econet.leads.sms;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/** app.alerts.* (env ALERT_PHONE, ALERT_EMAIL): comma-separated owner alert targets. */
@Component
@ConfigurationProperties(prefix = "app.alerts")
@Getter
@Setter
public class AlertSettings {
    private String phone = "";
    private String email = "";
    private int maxSmsPerDay = 30;

    public List<String> phones() {
        return split(phone);
    }

    public List<String> emails() {
        return split(email);
    }

    private static List<String> split(String value) {
        if (value == null) return List.of();
        return Arrays.stream(value.split("[,;]")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }
}
