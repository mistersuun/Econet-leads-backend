package com.econet.leads.service;

import com.econet.leads.config.MailSettings;
import com.econet.leads.dto.ContactDTO;
import com.econet.leads.dto.EmailDTOs;
import com.econet.leads.dto.EmailPreviewDTO;
import com.econet.leads.dto.EmailSendRequest;
import com.econet.leads.dto.EmailSendResponse;
import com.econet.leads.dto.EmailTemplateUpdateRequest;
import com.econet.leads.dto.PricingDTOs.Estimate;
import com.econet.leads.dto.PricingDTOs.EstimateLine;
import com.econet.leads.exception.ApiException;
import com.econet.leads.mapper.DtoMapper;
import com.econet.leads.model.Business;
import com.econet.leads.model.Contact;
import com.econet.leads.model.EmailTemplate;
import com.econet.leads.model.User;
import com.econet.leads.repository.BusinessRepository;
import com.econet.leads.repository.ContactRepository;
import com.econet.leads.repository.EmailTemplateRepository;
import com.econet.leads.util.IcsBuilder;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Follow-up emails from templates ({{placeholders}}), optional estimate and ICS visit invite.
 * Sending needs SMTP (503 otherwise) and is limited to 20 per user per hour (429).
 */
@Service
@Slf4j
public class EmailService {

    public static final Set<String> TEMPLATE_KEYS = Set.of("quote_followup", "visit_confirmation", "missed_you", "intro");
    public static final int VISIT_DURATION_MINUTES = 60;
    static final String ICS_FILENAME = "visite.ics";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");
    private static final DateTimeFormatter VISIT_FR = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy 'à' H 'h' mm", Locale.CANADA_FRENCH);
    private static final DateTimeFormatter VISIT_EN = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.CANADA);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final MailSettings settings;
    private final EmailTemplateRepository templateRepository;
    private final BusinessRepository businessRepository;
    private final ContactRepository contactRepository;
    private final PricingService pricingService;
    private final EmailRateLimiter rateLimiter;
    private final Clock clock;
    private final String companyName;

    public EmailService(ObjectProvider<JavaMailSender> mailSender,
                        MailSettings settings,
                        EmailTemplateRepository templateRepository,
                        BusinessRepository businessRepository,
                        ContactRepository contactRepository,
                        PricingService pricingService,
                        EmailRateLimiter rateLimiter,
                        Clock clock,
                        @Value("${app.company.name:EcoNet Propreté}") String companyName) {
        this.mailSender = mailSender;
        this.settings = settings;
        this.templateRepository = templateRepository;
        this.businessRepository = businessRepository;
        this.contactRepository = contactRepository;
        this.pricingService = pricingService;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.companyName = companyName;
    }

    // ------------------------------------------------------------------ status / templates

    public EmailDTOs.Status status() {
        boolean configured = settings.isConfigured() && mailSender.getIfAvailable() != null;
        return new EmailDTOs.Status(configured,
                configured ? settings.effectiveFrom() : null,
                configured ? settings.getFromName() : null,
                rateLimiter.limit());
    }

    @Transactional(readOnly = true)
    public List<EmailDTOs.Template> templates() {
        return templateRepository.findAllByOrderByKeyAscLanguageAsc().stream().map(EmailService::toDto).toList();
    }

    @Transactional
    public EmailDTOs.Template updateTemplate(UUID id, EmailTemplateUpdateRequest request) {
        EmailTemplate template = templateRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Email template not found: " + id));
        if (request.getSubject() != null) {
            if (request.getSubject().isBlank()) throw ApiException.badRequest("subject must not be blank");
            template.setSubject(request.getSubject().trim());
        }
        if (request.getBody() != null) {
            if (request.getBody().isBlank()) throw ApiException.badRequest("body must not be blank");
            template.setBody(request.getBody());
        }
        if (request.getActive() != null) {
            template.setActive(request.getActive());
        }
        return toDto(templateRepository.saveAndFlush(template));
    }

    static EmailDTOs.Template toDto(EmailTemplate t) {
        return new EmailDTOs.Template(t.getId(), t.getKey(), t.getLanguage(), t.getSubject(), t.getBody(),
                Boolean.TRUE.equals(t.getActive()), t.getUpdatedAt());
    }

    // ------------------------------------------------------------------ preview / send

    record Rendered(String subject, String body, String to, String language) {
    }

    @Transactional(readOnly = true)
    public EmailPreviewDTO preview(UUID leadId, EmailSendRequest request, User caller) {
        Business lead = businessRepository.findById(leadId)
                .orElseThrow(() -> ApiException.notFound("Lead not found: " + leadId));
        Rendered r = render(lead, request, caller);
        return new EmailPreviewDTO(r.subject(), r.body());
    }

    @Transactional
    public EmailSendResponse send(UUID leadId, EmailSendRequest request, User caller) {
        JavaMailSender sender = settings.isConfigured() ? mailSender.getIfAvailable() : null;
        if (sender == null) {
            throw ApiException.serviceUnavailable("Email is not configured (set SMTP_HOST, SMTP_PORT, SMTP_USERNAME, SMTP_PASSWORD)");
        }
        Business lead = businessRepository.findByIdForUpdate(leadId)
                .orElseThrow(() -> ApiException.notFound("Lead not found: " + leadId));
        Rendered r = render(lead, request, caller);
        if (r.to() == null) {
            throw ApiException.badRequest("No recipient: pass 'to' or set an email on the lead / its qualification");
        }
        rateLimiter.acquire(caller.getId());

        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        try {
            MimeMessage message = sender.createMimeMessage();
            boolean withInvite = request.getVisitAt() != null;
            MimeMessageHelper helper = new MimeMessageHelper(message, withInvite, StandardCharsets.UTF_8.name());
            helper.setFrom(settings.effectiveFrom(), settings.getFromName());
            helper.setTo(r.to());
            helper.setSubject(r.subject());
            helper.setText(r.body(), false);
            if (withInvite) {
                String ics = IcsBuilder.build(
                        UUID.randomUUID() + "@econet-leads",
                        "Visite " + companyName + " – " + lead.getBusinessName(),
                        lead.getFullAddress(),
                        r.body(),
                        request.getVisitAt(), VISIT_DURATION_MINUTES,
                        settings.getFromName(), settings.effectiveFrom(),
                        QualificationService.text(lead.getQualification(), "decisionMaker"), r.to(),
                        clock.instant());
                helper.addAttachment(ICS_FILENAME, new ByteArrayResource(ics.getBytes(StandardCharsets.UTF_8)),
                        "text/calendar; charset=UTF-8; method=REQUEST");
            }
            sender.send(message);
        } catch (MessagingException | UnsupportedEncodingException | MailException e) {
            log.error("Email to lead {} failed: {}", lead.getId(), e.getMessage());
            throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "Email could not be sent: " + e.getMessage());
        }

        Contact contact = new Contact();
        contact.setBusiness(lead);
        contact.setUser(caller);
        contact.setContactType(Contact.ContactType.EMAIL);
        contact.setContactDate(now);
        contact.setContactStatus("EMAIL_SENT");
        contact.setContactPerson(QualificationService.text(lead.getQualification(), "decisionMaker"));
        contact.setNotes(r.subject() + "\n\n" + r.body());
        contact = contactRepository.saveAndFlush(contact);

        lead.setContactCount((lead.getContactCount() != null ? lead.getContactCount() : 0) + 1);
        lead.setLastContactedAt(now);
        businessRepository.saveAndFlush(lead);

        log.info("Email '{}' sent to lead {} by {}", request.getTemplateKey(), lead.getId(), caller.getUsername());
        return new EmailSendResponse(DtoMapper.toDto(contact), new EmailPreviewDTO(r.subject(), r.body()));
    }

    // ------------------------------------------------------------------ rendering

    Rendered render(Business lead, EmailSendRequest request, User caller) {
        String key = request.getTemplateKey() == null ? "" : request.getTemplateKey().trim().toLowerCase(Locale.ROOT);
        if (!TEMPLATE_KEYS.contains(key)) {
            throw ApiException.badRequest("templateKey must be one of " + TEMPLATE_KEYS);
        }
        Map<String, Object> qualification = lead.getQualification();
        String language = request.getLanguage() != null && !request.getLanguage().isBlank()
                ? request.getLanguage().trim().toLowerCase(Locale.ROOT)
                : "en".equals(QualificationService.text(qualification, "language")) ? "en" : "fr";
        if (!QualificationService.LANGUAGES.contains(language)) {
            throw ApiException.badRequest("language must be fr or en");
        }
        if ("visit_confirmation".equals(key) && request.getVisitAt() == null) {
            throw ApiException.badRequest("visitAt is required for the visit_confirmation template");
        }
        EmailTemplate template = templateRepository.findByKeyAndLanguageAndActiveTrue(key, language)
                .orElseThrow(() -> ApiException.notFound("No active email template " + key + " (" + language + ")"));

        Locale locale = "en".equals(language) ? Locale.CANADA : Locale.CANADA_FRENCH;
        Map<String, String> values = new LinkedHashMap<>();
        values.put("businessName", nz(lead.getBusinessName()));
        values.put("city", nz(lead.getAddressCity()));
        values.put("address", nz(lead.getFullAddress()));
        values.put("decisionMaker", nz(QualificationService.text(qualification, "decisionMaker")));
        values.put("callerName", caller.getUsername());
        values.put("companyName", companyName);
        values.put("extraNote", request.getExtraNote() == null ? "" : request.getExtraNote().trim());
        values.put("visitAt", request.getVisitAt() == null ? "" : formatVisit(request.getVisitAt(), language));
        if (request.getEstimate() != null) {
            Estimate estimate = pricingService.estimate(request.getEstimate());
            values.put("estimateLines", estimateLines(estimate, language, locale));
            values.put("estimateSubtotal", money(estimate.subtotal(), locale));
            values.put("estimateTaxes", money(estimate.taxes(), locale));
            values.put("estimateTotal", estimate.requiresQuote()
                    ? ("en".equals(language) ? "on quote" : "sur soumission")
                    : money(estimate.total(), locale));
        } else {
            values.put("estimateLines", "");
            values.put("estimateSubtotal", "");
            values.put("estimateTaxes", "");
            values.put("estimateTotal", "");
        }

        String to = request.getTo() != null && !request.getTo().isBlank() ? request.getTo().trim()
                : firstNonBlank(QualificationService.text(qualification, "email"), lead.getEmail());
        if (to != null && !to.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw ApiException.badRequest("'" + to + "' is not a valid email address");
        }
        return new Rendered(tidy(substitute(template.getSubject(), values)), tidy(substitute(template.getBody(), values)), to, language);
    }

    static String substitute(String text, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(values.getOrDefault(m.group(1), "")));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** "Bonjour ," when the decision maker is unknown -> "Bonjour,"; collapses blank runs left by empty placeholders. */
    static String tidy(String text) {
        return text.replaceAll("[ \\t]+,", ",")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    static String formatVisit(LocalDateTime visitAt, String language) {
        return "en".equals(language) ? VISIT_EN.format(visitAt) : VISIT_FR.format(visitAt);
    }

    static String estimateLines(Estimate e, String language, Locale locale) {
        boolean en = "en".equals(language);
        StringBuilder sb = new StringBuilder();
        String service = en ? PricingService.SERVICE_LABELS_EN.get(e.service()) : PricingService.SERVICE_LABELS_FR.get(e.service());
        if (e.requiresQuote()) {
            sb.append(service).append(en ? ": custom quote required (over 2,500 sq ft)" : " : soumission sur mesure requise (plus de 2 500 pi²)");
            return sb.toString();
        }
        sb.append(service).append(" : ").append(money(e.servicePrice(), locale)).append('\n');
        for (EstimateLine line : e.addOns()) {
            sb.append("+ ").append(en ? line.labelEn() : line.labelFr()).append(" : ").append(money(line.price(), locale)).append('\n');
        }
        if (e.discountRate() > 0) {
            String freq = en ? e.frequency() : switch (e.frequency()) {
                case "weekly" -> "hebdomadaire";
                case "bi-weekly" -> "aux deux semaines";
                case "monthly" -> "mensuelle";
                default -> e.frequency();
            };
            sb.append(en ? "Frequency discount (" : "Rabais fréquence ").append(en ? "" : freq + " (")
                    .append(Math.round(e.discountRate() * 100)).append(en ? "%, " + freq + ")" : " %)")
                    .append(" : -").append(money(e.discountAmount(), locale)).append('\n');
        }
        sb.append(en ? "Subtotal" : "Sous-total").append(" : ").append(money(e.subtotal(), locale)).append('\n');
        sb.append(en ? "Taxes (GST + QST 14.975%)" : "Taxes (TPS + TVQ 14,975 %)").append(" : ").append(money(e.taxes(), locale));
        return sb.toString();
    }

    static String money(Number amount, Locale locale) {
        if (amount == null) return "";
        NumberFormat f = NumberFormat.getCurrencyInstance(locale);
        f.setCurrency(java.util.Currency.getInstance("CAD"));
        // plain-text emails: regular spaces instead of the (narrow) no-break spaces of fr-CA formatting
        return f.format(amount.doubleValue()).replace("CA$", "$").replace('\u00A0', ' ').replace('\u202F', ' ');
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }
}
