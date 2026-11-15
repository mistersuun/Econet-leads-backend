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

/** Old requests imported late never alert the owner, and test alerts do not use up the daily budget. */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.sms.account-sid=ACtest", "app.sms.auth-token=t", "app.sms.from=+14385550000",
        "app.alerts.phone=514-555-0002", "app.alerts.max-sms-per-day=2"
})
class AlertBacklogAndTestAlertTest {

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
    void backlogIsSilentAndTestAlertsDoNotCountTowardsTheCap() {
        alertService.handle(new InboundLeadEvent(UUID.randomUUID(), "Ancienne", "contact", "Demande web", null, "514-555-0100", true));
        assertThat(TWILIO.calls).isEmpty();

        for (int i = 0; i < 3; i++) {
            alertService.sendTest();
        }
        assertThat(TWILIO.calls).hasSize(3);

        alertService.handle(new InboundLeadEvent(UUID.randomUUID(), "Nouvelle A", "contact", "Demande web", null, "514-555-0101"));
        alertService.handle(new InboundLeadEvent(UUID.randomUUID(), "Nouvelle B", "contact", "Demande web", null, "514-555-0102"));
        assertThat(TWILIO.calls).hasSize(5);
    }
}
