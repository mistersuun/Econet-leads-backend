package com.econet.leads.sms;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class SmsServiceTest {

    @Test
    void normalizesCanadianNumbersToE164() {
        assertThat(SmsService.toE164("(514) 555-1234")).isEqualTo("+15145551234");
        assertThat(SmsService.toE164("1-514-555-1234")).isEqualTo("+15145551234");
        assertThat(SmsService.toE164("+1 514 555 1234")).isEqualTo("+15145551234");
        assertThat(SmsService.toE164("+33 6 12 34 56 78")).isEqualTo("+33612345678");
        assertThat(SmsService.toE164("555-12")).isNull();
        assertThat(SmsService.toE164(null)).isNull();
    }

    @Test
    void masksAllButTheLastFourDigits() {
        assertThat(SmsService.mask("+15145551234")).isEqualTo("***1234").doesNotContain("514");
        assertThat(SmsService.mask("12")).isEqualTo("****");
    }

    @Test
    void providerMessagesLoseTheFullNumber() {
        assertThat(SmsService.maskNumbers("The 'To' number +15145551234 is not a valid phone number."))
                .doesNotContain("5145551234").contains("***1234");
        assertThat(SmsService.maskNumbers("Region not enabled: +1 (514) 555-1234.")).doesNotContain("555-1234").contains("***1234");
        assertThat(SmsService.maskNumbers("Authenticate")).isEqualTo("Authenticate");
    }

    @Test
    void contactLineComesBeforeTheOptOutLine() {
        assertThat(SmsTemplates.render("missed_call", "fr", "Clinique", "alice", "EcoNet", "514 555-0123 econet.ca"))
                .contains("514 555-0123 econet.ca Répondez STOP").endsWith("Répondez STOP pour vous désabonner.");
    }

    @Test
    void templatesEndWithTheOptOutLine() {
        for (String key : SmsTemplates.KEYS) {
            assertThat(SmsTemplates.render(key, "fr", "Clinique Soleil", "alice", "EcoNet"))
                    .contains("Clinique Soleil").contains("alice").endsWith("Répondez STOP pour vous désabonner.")
                    .doesNotContain("{");
            assertThat(SmsTemplates.render(key, "en", "Sun Clinic", "alice", "EcoNet")).endsWith("Reply STOP to opt out.");
        }
    }

    @Test
    void sendsFormBodyWithBasicAuthAndReportsProviderErrors() {
        FakeTwilio twilio = new FakeTwilio();
        try {
            SmsSettings settings = new SmsSettings();
            settings.setAccountSid("ACtest");
            settings.setAuthToken("secret-token");
            settings.setFrom("+14385550000");
            settings.setApiBase(twilio.baseUrl());
            SmsService service = new SmsService(settings, new ObjectMapper());

            SmsService.Result ok = service.send("+15145551234", "Bonjour é");
            assertThat(ok.sent()).isTrue();
            assertThat(ok.sid()).startsWith("SM");
            FakeTwilio.Call call = twilio.calls.get(0);
            assertThat(call.path()).isEqualTo("/2010-04-01/Accounts/ACtest/Messages.json");
            assertThat(call.authorization()).isEqualTo("Basic " + Base64.getEncoder().encodeToString("ACtest:secret-token".getBytes()));
            assertThat(call.form()).containsEntry("To", "+15145551234").containsEntry("From", "+14385550000").containsEntry("Body", "Bonjour é");

            twilio.nextStatus = 400;
            SmsService.Result bad = service.send("+15145551234", "x");
            assertThat(bad.sent()).isFalse();
            assertThat(bad.error()).contains("Invalid 'To'");
        } finally {
            twilio.server.stop(0);
        }
    }

    @Test
    void unreachableProviderIsAFailureNotAnException() {
        SmsSettings settings = new SmsSettings();
        settings.setAccountSid("AC");
        settings.setAuthToken("t");
        settings.setFrom("+14385550000");
        settings.setApiBase("http://127.0.0.1:1");
        SmsService.Result r = new SmsService(settings, new ObjectMapper()).send("+15145551234", "x");
        assertThat(r.sent()).isFalse();
        assertThat(r.error()).isNotBlank();
    }

    @Test
    void notConfiguredWithoutAllThreeValues() {
        SmsSettings settings = new SmsSettings();
        settings.setAccountSid("AC");
        settings.setAuthToken("t");
        assertThat(settings.isConfigured()).isFalse();
        assertThat(new SmsService(settings, new ObjectMapper()).send("+15145551234", "x").sent()).isFalse();
    }
}
