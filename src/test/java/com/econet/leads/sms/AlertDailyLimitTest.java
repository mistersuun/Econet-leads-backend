package com.econet.leads.sms;

import com.econet.leads.integration.website.InboundLeadEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.sms.account-sid=ACtest", "app.sms.auth-token=t", "app.sms.from=+14385550000",
        "app.alerts.phone=514-555-0001", "app.alerts.max-sms-per-day=2"
})
class AlertDailyLimitTest {

    static final FakeTwilio TWILIO = new FakeTwilio();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.sms.api-base", TWILIO::baseUrl);
    }

    @AfterAll
    static void stop() {
        TWILIO.server.stop(0);
    }

    @Autowired AlertService alertService;

    @Test
    void stopsTextingAfterTheDailyLimit() {
        for (int i = 0; i < 4; i++) {
            alertService.handle(new InboundLeadEvent(UUID.randomUUID(), "Lead " + i, "contact", "Demande web", null, "514-555-000" + i));
        }
        assertThat(TWILIO.calls).hasSize(2);
    }
}
