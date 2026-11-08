package com.econet.leads.integration.website;

import com.econet.leads.model.Business;
import com.econet.leads.model.Contact;
import com.econet.leads.model.DataSource;
import com.econet.leads.model.User;
import com.econet.leads.realtime.SseEventService;
import com.econet.leads.repository.ContactRepository;
import com.econet.leads.repository.DataSourceRepository;
import com.econet.leads.repository.UserRepository;
import com.econet.leads.service.BusinessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Inbound leads from the website: what happens to a lead the first time it is imported (history note
 * and the {@code lead.inbound} SSE event), and the one-time activation of the source at startup.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebsiteRequestsService {

    public static final String EVENT_INBOUND = "lead.inbound";

    private final WebsiteDb websiteDb;
    private final WebsiteRequestsImporter importer;
    private final SseEventService events;
    private final ContactRepository contactRepository;
    private final UserRepository userRepository;
    private final DataSourceRepository dataSourceRepository;
    private final Clock clock;

    /** Called for every upserted website record; only a newly created lead is announced. */
    public void afterUpsert(BusinessService.UpsertResult result) {
        WebsiteRequestsImporter.Inbound inbound = importer.inboundOf(result.business().getExternalId());
        if (!result.created() || inbound == null) {
            return;
        }
        Business lead = result.business();
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        try {
            // The history needs an author: the oldest active administrator stands in for the website
            User author = userRepository.findFirstByRoleAndActiveTrueOrderByCreatedAtAsc(User.UserRole.ADMIN).orElse(null);
            if (author != null) {
                Contact note = new Contact();
                note.setBusiness(lead);
                note.setUser(author);
                note.setContactType(Contact.ContactType.NOTE);
                note.setContactStatus("INBOUND_REQUEST");
                note.setContactDate(now);
                note.setNotes(inbound.note());
                contactRepository.save(note);
            }
        } catch (RuntimeException e) {
            log.warn("Could not write the history note of website lead {}: {}", lead.getId(), e.toString());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("leadId", lead.getId());
        payload.put("leadName", lead.getBusinessName());
        payload.put("kind", inbound.kind());
        payload.put("at", now);
        // additive, so the toast can read "Résidentiel · 261 $" without a second request
        payload.put("businessType", lead.getBusinessType());
        payload.put("estimatedValue", lead.getEstimatedValue());
        events.publish(EVENT_INBOUND, payload);
    }

    /** Configured for the first time: switch the source on (once; an admin who later turns it off keeps it off). */
    @EventListener(ApplicationReadyEvent.class)
    public void activateWhenConfigured() {
        if (!websiteDb.isConfigured()) {
            return;
        }
        dataSourceRepository.findBySourceName(WebsiteRequestsImporter.SOURCE_NAME).ifPresent(ds -> {
            if (!Boolean.TRUE.equals(ds.getActive()) && ds.getLastSync() == null) {
                ds.setActive(true);
                dataSourceRepository.save(ds);
                log.info("Website requests source activated (WEBSITE_DB_URL is set)");
            }
        });
    }

    public java.util.Optional<DataSource> source() {
        return dataSourceRepository.findBySourceName(WebsiteRequestsImporter.SOURCE_NAME);
    }
}
