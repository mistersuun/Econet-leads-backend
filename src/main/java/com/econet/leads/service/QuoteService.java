package com.econet.leads.service;

import com.econet.leads.config.MailSettings;
import com.econet.leads.dto.LeadStatusUpdateRequest;
import com.econet.leads.dto.PricingDTOs.Estimate;
import com.econet.leads.dto.PricingDTOs.EstimateInput;
import com.econet.leads.dto.QuoteDTOs;
import com.econet.leads.exception.ApiException;
import com.econet.leads.model.Business;
import com.econet.leads.model.Contact;
import com.econet.leads.model.LeadStatus;
import com.econet.leads.model.Quote;
import com.econet.leads.model.User;
import com.econet.leads.realtime.SseEventService;
import com.econet.leads.repository.BusinessRepository;
import com.econet.leads.repository.ContactRepository;
import com.econet.leads.repository.QuoteRepository;
import com.econet.leads.repository.UserRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Quotes: created from the same {@link PricingService} estimate as the website, sent by email with
 * the PDF and a public accept link, accepted / declined by the prospect through that link (a lead
 * moves to WON through {@link LeadService#updateStatus}, the same path as the app's status changes).
 */
@Service
@Slf4j
public class QuoteService {

    public static final String EVENT_QUOTE_ACCEPTED = "quote.accepted";
    public static final int VALID_DAYS = 30;
    static final int MAX_NAME = 200;
    static final int MAX_REASON = 500;

    /** The outcome of a public accept / decline call. */
    public enum PublicResult { DONE, ALREADY_ACCEPTED, EXPIRED, DECLINED, ACCEPTED, INVALID_INPUT }

    private final QuoteRepository quoteRepository;
    private final BusinessRepository businessRepository;
    private final ContactRepository contactRepository;
    private final UserRepository userRepository;
    private final PricingService pricingService;
    private final QuotePdfService pdfService;
    private final LeadService leadService;
    private final SseEventService events;
    private final PublicLinkService links;
    private final EmailRateLimiter rateLimiter;
    private final MailSettings mailSettings;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final String companyName;
    private final ClientService clientService;
    private final Object numberLock = new Object();

    public QuoteService(QuoteRepository quoteRepository, BusinessRepository businessRepository,
                        ContactRepository contactRepository, UserRepository userRepository,
                        PricingService pricingService, QuotePdfService pdfService, LeadService leadService,
                        SseEventService events, PublicLinkService links, EmailRateLimiter rateLimiter,
                        MailSettings mailSettings, ObjectProvider<JavaMailSender> mailSender, Clock clock,
                        PlatformTransactionManager transactionManager,
                        ClientService clientService,
                        @Value("${app.company.name:EcoNet Propreté}") String companyName) {
        this.clientService = clientService;
        this.quoteRepository = quoteRepository;
        this.businessRepository = businessRepository;
        this.contactRepository = contactRepository;
        this.userRepository = userRepository;
        this.pricingService = pricingService;
        this.pdfService = pdfService;
        this.leadService = leadService;
        this.events = events;
        this.links = links;
        this.rateLimiter = rateLimiter;
        this.mailSettings = mailSettings;
        this.mailSender = mailSender;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.companyName = companyName;
    }

    // ------------------------------------------------------------------ CRM side

    public QuoteDTOs.Quote create(UUID leadId, QuoteDTOs.CreateRequest request, User caller) {
        synchronized (numberLock) { // sequential numbers; one instance by design (see SseEventService)
            return tx.execute(status -> doCreate(leadId, request, caller));
        }
    }

    private QuoteDTOs.Quote doCreate(UUID leadId, QuoteDTOs.CreateRequest request, User caller) {
        Business lead = businessRepository.findById(leadId)
                .orElseThrow(() -> ApiException.notFound("Lead not found: " + leadId));
        EstimateInput input = request.estimate();
        Estimate estimate = pricingService.estimate(input);
        if (estimate.requiresQuote()) {
            throw ApiException.badRequest("Cette superficie exige une soumission sur mesure: elle ne peut pas être calculée automatiquement");
        }
        String language = request.language() != null && !request.language().isBlank()
                ? request.language().trim().toLowerCase(Locale.ROOT)
                : "en".equals(QualificationService.text(lead.getQualification(), "language")) ? "en" : "fr";
        if (!QualificationService.LANGUAGES.contains(language)) {
            throw ApiException.badRequest("language must be fr or en");
        }
        boolean en = "en".equals(language);

        Quote quote = new Quote();
        quote.setBusiness(lead);
        quote.setLanguage(language);
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("service", estimate.service());
        stored.put("area", estimate.area());
        stored.put("propertyType", estimate.propertyType());
        stored.put("addOnIds", estimate.addOns().stream().map(l -> l.id()).toList());
        stored.put("frequency", estimate.frequency());
        quote.setInput(stored);
        quote.setLines(lines(estimate, en));
        quote.setSubtotal(BigDecimal.valueOf(estimate.subtotal()).setScale(2, RoundingMode.UNNECESSARY));
        quote.setTax(BigDecimal.valueOf(estimate.taxes()).setScale(2, RoundingMode.UNNECESSARY));
        quote.setTotal(BigDecimal.valueOf(estimate.total()).setScale(2, RoundingMode.UNNECESSARY));
        quote.setStatus(Quote.DRAFT);
        quote.setToken(PublicLinkService.newToken());
        LocalDate today = LocalDate.now(clock);
        quote.setValidUntil(today.plusDays(VALID_DAYS));
        quote.setCreatedBy(caller);
        quote.setNumber(nextNumber(today.getYear()));
        quote = quoteRepository.saveAndFlush(quote);
        log.info("Quote {} created on lead {} by {}", quote.getNumber(), leadId, caller.getUsername());
        return toDto(quote);
    }

    /** Lines whose amounts add up exactly to the subtotal: the discount line absorbs the website's rounding. */
    static List<Map<String, Object>> lines(Estimate e, boolean en) {
        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal running = BigDecimal.valueOf(e.servicePrice()).setScale(2, RoundingMode.HALF_UP);
        lines.add(line("service", QuoteLabels.service(e.service(), en), running));
        for (var addOn : e.addOns()) {
            BigDecimal price = BigDecimal.valueOf(addOn.price()).setScale(2, RoundingMode.UNNECESSARY);
            lines.add(line(addOn.id(), "+ " + (en ? addOn.labelEn() : addOn.labelFr()), price));
            running = running.add(price);
        }
        BigDecimal discount = BigDecimal.valueOf(e.subtotal()).setScale(2, RoundingMode.UNNECESSARY).subtract(running);
        if (discount.signum() != 0) {
            String label = (en ? "Frequency discount (" : "Rabais fréquence (")
                    + QuoteLabels.frequency(e.frequency(), en).toLowerCase(Locale.ROOT)
                    + ", " + Math.round(e.discountRate() * 100) + (en ? "%)" : " %)");
            lines.add(line("discount", label, discount));
        }
        return lines;
    }

    private static Map<String, Object> line(String id, String label, BigDecimal amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("amount", amount.doubleValue());
        return m;
    }

    private String nextNumber(int year) {
        String prefix = "Q-" + year + "-";
        int next = quoteRepository.findLastNumber(prefix + "%")
                .map(n -> Integer.parseInt(n.substring(prefix.length())) + 1)
                .orElse(1);
        return prefix + String.format("%04d", next);
    }

    @Transactional(readOnly = true)
    public List<QuoteDTOs.Quote> listForLead(UUID leadId) {
        if (!businessRepository.existsById(leadId)) {
            throw ApiException.notFound("Lead not found: " + leadId);
        }
        return quoteRepository.findByBusinessId(leadId).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public PdfFile pdf(UUID quoteId) {
        Quote quote = quoteRepository.findById(quoteId)
                .orElseThrow(() -> ApiException.notFound("Quote not found: " + quoteId));
        return new PdfFile(quote.getNumber() + ".pdf", pdfService.render(quote));
    }

    public record PdfFile(String filename, byte[] bytes) {
    }

    /** Emails the PDF and the accept link (SMTP-gated: 503), marks the quote sent and the lead QUOTE_SENT if it is lower. */
    @Transactional
    public QuoteDTOs.Quote send(UUID quoteId, QuoteDTOs.SendRequest request, User caller) {
        JavaMailSender sender = mailSettings.isConfigured() ? mailSender.getIfAvailable() : null;
        if (sender == null) {
            throw ApiException.serviceUnavailable("Courriel non configuré (définir SMTP_HOST, SMTP_PORT, SMTP_USERNAME, SMTP_PASSWORD)");
        }
        Quote quote = quoteRepository.findById(quoteId)
                .orElseThrow(() -> ApiException.notFound("Quote not found: " + quoteId));
        Business lead = businessRepository.findByIdForUpdate(quote.getBusiness().getId())
                .orElseThrow(() -> ApiException.notFound("Lead not found"));
        if (Quote.ACCEPTED.equals(quote.getStatus()) || Quote.DECLINED.equals(quote.getStatus())) {
            throw ApiException.conflict("Cette soumission est déjà " + (Quote.ACCEPTED.equals(quote.getStatus()) ? "acceptée" : "refusée"));
        }
        if (isExpired(quote) || Quote.EXPIRED.equals(quote.getStatus())) {
            throw ApiException.conflict("Cette soumission est expirée: créez-en une nouvelle");
        }
        String to = request != null && request.to() != null && !request.to().isBlank() ? request.to().trim()
                : firstNonBlank(QualificationService.text(lead.getQualification(), "email"), lead.getEmail());
        if (to == null) {
            throw ApiException.badRequest("No recipient: pass 'to' or set an email on the lead / its qualification");
        }
        if (!to.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw ApiException.badRequest("'" + to + "' is not a valid email address");
        }
        rateLimiter.acquire(caller.getId());

        boolean en = "en".equals(quote.getLanguage());
        Locale locale = en ? Locale.CANADA : Locale.CANADA_FRENCH;
        String link = links.quoteUrl(quote.getToken());
        String subject = (en ? "Your cleaning quote " : "Votre soumission d'entretien ménager ") + quote.getNumber() + " – " + companyName;
        String body = emailBody(quote, lead, caller, link, en, locale);
        byte[] pdf = pdfService.render(quote);
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(mailSettings.effectiveFrom(), mailSettings.getFromName());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);
            helper.addAttachment(quote.getNumber() + ".pdf", new ByteArrayResource(pdf), "application/pdf");
            sender.send(message);
        } catch (MessagingException | UnsupportedEncodingException | MailException e) {
            // the exception text carries the SMTP reply with the recipient's address: log only its type
            log.error("Quote email {} failed ({})", quote.getNumber(), e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Le courriel n'a pas pu être envoyé");
        }

        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        quote.setStatus(Quote.SENT);
        quote.setSentAt(now);
        quoteRepository.saveAndFlush(quote);

        Contact contact = new Contact();
        contact.setBusiness(lead);
        contact.setUser(caller);
        contact.setContactType(Contact.ContactType.EMAIL);
        contact.setContactDate(now);
        contact.setContactStatus("EMAIL_SENT");
        contact.setContactPerson(QualificationService.text(lead.getQualification(), "decisionMaker"));
        contact.setNotes("Soumission " + quote.getNumber() + " envoyée (" + money(quote.getTotal(), Locale.CANADA_FRENCH) + ")\n" + subject);
        contactRepository.saveAndFlush(contact);
        lead.setContactCount((lead.getContactCount() != null ? lead.getContactCount() : 0) + 1);
        lead.setLastContactedAt(now);
        businessRepository.saveAndFlush(lead);

        if (lead.getLeadStatus().ordinal() < LeadStatus.QUOTE_SENT.ordinal()) {
            LeadStatusUpdateRequest status = new LeadStatusUpdateRequest();
            status.setStatus(LeadStatus.QUOTE_SENT);
            status.setNote("Soumission " + quote.getNumber() + " envoyée");
            leadService.updateStatus(lead.getId(), status, caller.getId());
        }
        log.info("Quote {} sent for lead {} by {}", quote.getNumber(), lead.getId(), caller.getUsername());
        return toDto(quote);
    }

    private String emailBody(Quote quote, Business lead, User caller, String link, boolean en, Locale locale) {
        String contact = QualificationService.text(lead.getQualification(), "decisionMaker");
        String total = money(quote.getTotal(), locale);
        DateTimeFormatter dates = DateTimeFormatter.ofPattern(en ? "MMMM d, yyyy" : "d MMMM yyyy", locale);
        StringBuilder sb = new StringBuilder();
        if (en) {
            sb.append("Hello").append(contact != null && !contact.isBlank() ? " " + contact : "").append(",\n\n")
                    .append("Thank you for your interest. Please find our quote ").append(quote.getNumber())
                    .append(" attached (total ").append(total).append(", taxes included).\n\n")
                    .append("You can review it and accept it online here:\n").append(link).append("\n\n")
                    .append("The quote is valid until ").append(dates.format(quote.getValidUntil())).append(".\n\n")
                    .append("Do not hesitate to reply to this email with any question.\n\n");
        } else {
            sb.append("Bonjour").append(contact != null && !contact.isBlank() ? " " + contact : "").append(",\n\n")
                    .append("Merci de votre intérêt. Vous trouverez ci-joint notre soumission ").append(quote.getNumber())
                    .append(" (total ").append(total).append(", taxes incluses).\n\n")
                    .append("Vous pouvez la consulter et l'accepter en ligne ici :\n").append(link).append("\n\n")
                    .append("La soumission est valide jusqu'au ").append(dates.format(quote.getValidUntil())).append(".\n\n")
                    .append("N'hésitez pas à répondre à ce courriel pour toute question.\n\n");
        }
        sb.append(caller.getUsername()).append('\n').append(companyName);
        return sb.toString();
    }

    // ------------------------------------------------------------------ public side

    /** The quote behind a public token, or 404. Expires it lazily. */
    @Transactional
    public Quote findPublic(String token) {
        if (!PublicLinkService.looksLikeToken(token)) {
            throw ApiException.notFound("Not found");
        }
        Quote quote = quoteRepository.findByToken(token)
                .filter(q -> PublicLinkService.secretsEqual(q.getToken(), token))
                .orElseThrow(() -> ApiException.notFound("Not found"));
        expireIfNeeded(quote);
        return quote;
    }

    @Transactional(readOnly = true)
    public byte[] publicPdf(String token) {
        if (!PublicLinkService.looksLikeToken(token)) {
            throw ApiException.notFound("Not found");
        }
        Quote quote = quoteRepository.findByToken(token)
                .filter(q -> PublicLinkService.secretsEqual(q.getToken(), token))
                .orElseThrow(() -> ApiException.notFound("Not found"));
        return pdfService.render(quote);
    }

    /** Idempotent: a second accept changes nothing and reports ALREADY_ACCEPTED. */
    @Transactional
    public PublicResult accept(String token, String fullName, boolean agreed, String clientIp) {
        Quote quote = lockPublic(token);
        if (Quote.ACCEPTED.equals(quote.getStatus())) {
            return PublicResult.ALREADY_ACCEPTED;
        }
        if (expireIfNeeded(quote)) {
            return PublicResult.EXPIRED;
        }
        if (Quote.DECLINED.equals(quote.getStatus())) {
            return PublicResult.DECLINED;
        }
        String name = fullName == null ? "" : fullName.strip().replaceAll("\\s+", " ");
        if (name.length() < 2 || name.length() > MAX_NAME || !agreed) {
            return PublicResult.INVALID_INPUT;
        }
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        quote.setStatus(Quote.ACCEPTED);
        quote.setAcceptedAt(now);
        quote.setAcceptedName(name);
        quote.setAcceptedIpHash(links.hmacHex(clientIp + "|" + quote.getId()));
        quoteRepository.saveAndFlush(quote);

        // the lead's other open quotes are superseded: their links must not be acceptable any more
        for (Quote other : quoteRepository.findByBusinessId(quote.getBusiness().getId())) {
            if (!other.getId().equals(quote.getId())
                    && (Quote.SENT.equals(other.getStatus()) || Quote.DRAFT.equals(other.getStatus()))) {
                other.setStatus(Quote.EXPIRED);
                quoteRepository.save(other);
            }
        }

        Business lead = quote.getBusiness();
        Optional<User> actor = actingUser(quote, lead);
        if (actor.isPresent()) {
            LeadStatusUpdateRequest status = new LeadStatusUpdateRequest();
            status.setStatus(LeadStatus.WON);
            status.setNote("Soumission " + quote.getNumber() + " acceptée en ligne par " + name);
            leadService.updateStatus(lead.getId(), status, actor.get().getId());
        } else {
            log.warn("Quote {} accepted but no user available to record the WON status", quote.getNumber());
        }
        clientService.refreshFromAcceptedQuote(lead.getId());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("leadId", lead.getId());
        payload.put("leadName", lead.getBusinessName());
        payload.put("quoteId", quote.getId());
        payload.put("total", quote.getTotal());
        events.publish(EVENT_QUOTE_ACCEPTED, payload);
        log.info("Quote {} accepted online (lead {})", quote.getNumber(), lead.getId());
        return PublicResult.DONE;
    }

    @Transactional
    public PublicResult decline(String token, String reason) {
        Quote quote = lockPublic(token);
        if (Quote.ACCEPTED.equals(quote.getStatus())) {
            return PublicResult.ACCEPTED;
        }
        if (Quote.DECLINED.equals(quote.getStatus())) {
            return PublicResult.DECLINED;
        }
        if (expireIfNeeded(quote)) {
            return PublicResult.EXPIRED;
        }
        String stripped = reason == null ? "" : reason.strip();
        final String text = stripped.isEmpty() ? null : stripped.substring(0, Math.min(stripped.length(), MAX_REASON));
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        quote.setStatus(Quote.DECLINED);
        quote.setDeclinedAt(now);
        quote.setDeclineReason(text);
        quoteRepository.saveAndFlush(quote);

        Business lead = quote.getBusiness();
        actingUser(quote, lead).ifPresent(user -> {
            Contact note = new Contact();
            note.setBusiness(lead);
            note.setUser(user);
            note.setContactType(Contact.ContactType.NOTE);
            note.setContactDate(now);
            note.setContactStatus("QUOTE_DECLINED");
            note.setNotes("Soumission " + quote.getNumber() + " refusée en ligne" + (text != null ? " : " + text : ""));
            contactRepository.save(note);
        });
        log.info("Quote {} declined online (lead {})", quote.getNumber(), lead.getId());
        return PublicResult.DONE;
    }

    private Quote lockPublic(String token) {
        if (!PublicLinkService.looksLikeToken(token)) {
            throw ApiException.notFound("Not found");
        }
        return quoteRepository.findByTokenForUpdate(token)
                .filter(q -> PublicLinkService.secretsEqual(q.getToken(), token))
                .orElseThrow(() -> ApiException.notFound("Not found"));
    }

    /** The user to attribute system-made history to: the quote's creator, else the lead's owner, else an admin. */
    private Optional<User> actingUser(Quote quote, Business lead) {
        if (quote.getCreatedBy() != null) return Optional.of(quote.getCreatedBy());
        if (lead.getAssignedTo() != null) return Optional.of(lead.getAssignedTo());
        return userRepository.findFirstByRoleAndActiveTrueOrderByCreatedAtAsc(User.UserRole.ADMIN);
    }

    // ------------------------------------------------------------------ helpers

    boolean isExpired(Quote quote) {
        return (Quote.DRAFT.equals(quote.getStatus()) || Quote.SENT.equals(quote.getStatus()))
                && quote.getValidUntil().isBefore(LocalDate.now(clock));
    }

    /** Persists the expiry of an open quote past its validity; true when the quote is (now) expired. */
    private boolean expireIfNeeded(Quote quote) {
        if (isExpired(quote)) {
            quote.setStatus(Quote.EXPIRED);
            quoteRepository.save(quote);
        }
        return Quote.EXPIRED.equals(quote.getStatus());
    }

    String effectiveStatus(Quote quote) {
        return isExpired(quote) ? Quote.EXPIRED : quote.getStatus();
    }

    QuoteDTOs.Quote toDto(Quote q) {
        return new QuoteDTOs.Quote(q.getId(), q.getBusiness().getId(), q.getNumber(), q.getLanguage(), q.getInput(),
                q.getLines(), q.getSubtotal(), q.getTax(), q.getTotal(), effectiveStatus(q), q.getValidUntil(),
                links.quoteUrl(q.getToken()), links.quotePdfUrl(q.getToken()),
                q.getCreatedBy() != null ? q.getCreatedBy().getUsername() : null,
                q.getCreatedAt(), q.getSentAt(), q.getAcceptedAt(), q.getAcceptedName(), q.getDeclineReason());
    }

    static String money(BigDecimal amount, Locale locale) {
        return EmailService.money(amount, locale);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }
}
