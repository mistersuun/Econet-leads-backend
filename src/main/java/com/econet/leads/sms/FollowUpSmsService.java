package com.econet.leads.sms;

import com.econet.leads.exception.ApiException;
import com.econet.leads.model.Business;
import com.econet.leads.model.Contact;
import com.econet.leads.model.User;
import com.econet.leads.repository.BusinessRepository;
import com.econet.leads.repository.ContactRepository;
import com.econet.leads.service.QualificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

/** Follow-up text to a prospect after a missed call / voicemail / quote, with recorded verbal consent. */
@Service
@Slf4j
public class FollowUpSmsService {

    private final SmsService smsService;
    private final AlertService alertService; // shares the sms_messages log writer
    private final SmsRateLimiter rateLimiter;
    private final BusinessRepository businessRepository;
    private final ContactRepository contactRepository;
    private final Clock clock;
    private final String companyName;
    private final String companyContact;

    public FollowUpSmsService(SmsService smsService, AlertService alertService, SmsRateLimiter rateLimiter,
                              BusinessRepository businessRepository, ContactRepository contactRepository, Clock clock,
                              @Value("${app.company.name:EcoNet Propreté}") String companyName,
                              @Value("${app.company.contact:}") String companyContact) {
        this.companyContact = companyContact;
        this.smsService = smsService;
        this.alertService = alertService;
        this.rateLimiter = rateLimiter;
        this.businessRepository = businessRepository;
        this.contactRepository = contactRepository;
        this.clock = clock;
        this.companyName = companyName;
    }

    @Transactional(readOnly = true)
    public String preview(UUID leadId, String templateKey, String language, User caller) {
        Business lead = businessRepository.findById(leadId).orElseThrow(() -> ApiException.notFound("Lead introuvable : " + leadId));
        return render(lead, templateKey, language, caller);
    }

    /** Not transactional on purpose: the failed-attempt log row must survive the 502 it precedes. */
    public String send(UUID leadId, String templateKey, String language, Boolean consent, User caller) {
        if (!Boolean.TRUE.equals(consent)) {
            throw ApiException.badRequest("Le consentement du client est requis pour envoyer un texto");
        }
        if (!smsService.isConfigured()) {
            throw ApiException.serviceUnavailable("Les textos ne sont pas configurés (TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, TWILIO_FROM)");
        }
        Business lead = businessRepository.findById(leadId)
                .orElseThrow(() -> ApiException.notFound("Lead introuvable : " + leadId));
        String body = render(lead, templateKey, language, caller);
        String to = SmsService.toE164(firstNonBlank(lead.getPhone(), QualificationService.text(lead.getQualification(), "directPhone")));
        if (to == null) {
            throw ApiException.badRequest("Ce lead n'a pas de numéro de téléphone valide pour un texto");
        }
        rateLimiter.acquire(caller.getId());

        SmsService.Result result = smsService.send(to, body);
        alertService.record(lead.getId(), caller.getId(), to, SmsMessage.FOLLOW_UP, result, body);
        if (!result.sent()) {
            throw ApiException.badGateway("Le texto n'a pas pu être envoyé : " + result.error());
        }

        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        Contact contact = new Contact();
        contact.setBusiness(lead);
        contact.setUser(caller);
        contact.setContactType(Contact.ContactType.NOTE); // the contact_type enum has no SMS value
        contact.setContactDate(now);
        contact.setContactStatus("SMS_SENT");
        contact.setContactPerson(QualificationService.text(lead.getQualification(), "decisionMaker"));
        contact.setNotes("Texto envoyé : " + body + "\nConsentement verbal obtenu par " + caller.getUsername());
        contactRepository.save(contact);

        lead.setContactCount((lead.getContactCount() != null ? lead.getContactCount() : 0) + 1);
        lead.setLastContactedAt(now);
        businessRepository.save(lead);
        log.info("Follow-up SMS '{}' sent to lead {} by {}", templateKey, lead.getId(), caller.getUsername());
        return "sent";
    }

    private String render(Business lead, String templateKey, String language, User caller) {
        if (lead.getLeadStatus() == com.econet.leads.model.LeadStatus.DO_NOT_CALL) {
            throw ApiException.conflict("Ce lead ne doit pas être contacté (Ne pas appeler)");
        }
        String key = templateKey == null ? "" : templateKey.trim().toLowerCase(Locale.ROOT);
        if (!SmsTemplates.KEYS.contains(key)) {
            throw ApiException.badRequest("templateKey doit être l'un de " + SmsTemplates.KEYS);
        }
        String lang = language != null && !language.isBlank() ? language.trim().toLowerCase(Locale.ROOT)
                : "en".equals(QualificationService.text(lead.getQualification(), "language")) ? "en" : "fr";
        if (!QualificationService.LANGUAGES.contains(lang)) {
            throw ApiException.badRequest("language doit être fr ou en");
        }
        return SmsTemplates.render(key, lang, lead.getBusinessName(), caller.getUsername(), companyName, companyContact);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
