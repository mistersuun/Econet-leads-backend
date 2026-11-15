package com.econet.leads.service;

import com.econet.leads.config.MailSettings;
import com.econet.leads.dto.ClientDTOs.ClientDTO;
import com.econet.leads.dto.ClientDTOs.ClientsSummary;
import com.econet.leads.dto.ClientDTOs.ReviewConfig;
import com.econet.leads.dto.ClientDTOs.ReviewResult;
import com.econet.leads.dto.NotificationDTO;
import com.econet.leads.exception.ApiException;
import com.econet.leads.model.Business;
import com.econet.leads.model.Client;
import com.econet.leads.model.Contact;
import com.econet.leads.model.Quote;
import com.econet.leads.model.User;
import com.econet.leads.repository.ClientRepository;
import com.econet.leads.repository.ContactRepository;
import com.econet.leads.repository.QuoteRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Clients after the sale: creation when a lead is won, editing, the first-cleaning and Google review
 * follow-ups, the MRR summary and the three client notifications.
 */
@Service
@Slf4j
public class ClientService {

    public static final String DEFAULT_SERVICE = "Entretien ménager";
    static final Duration REVIEW_DELAY = Duration.ofDays(7);
    static final int RENEWAL_WINDOW_DAYS = 30;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.CANADA_FRENCH);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");

    private final ClientRepository clientRepository;
    private final ContactRepository contactRepository;
    private final QuoteRepository quoteRepository;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final MailSettings mailSettings;
    private final EmailRateLimiter rateLimiter;
    private final Clock clock;
    private final String reviewUrl;
    private final String companyName;
    private final TransactionTemplate newTx;
    private final com.econet.leads.repository.BusinessRepository businessRepository;

    public ClientService(ClientRepository clientRepository,
                         ContactRepository contactRepository,
                         QuoteRepository quoteRepository,
                         ObjectProvider<JavaMailSender> mailSender,
                         MailSettings mailSettings,
                         EmailRateLimiter rateLimiter,
                         Clock clock,
                         PlatformTransactionManager transactionManager,
                         com.econet.leads.repository.BusinessRepository businessRepository,
                         @Value("${app.reviews.google-url:}") String reviewUrl,
                         @Value("${app.company.name:EcoNet Propreté}") String companyName) {
        this.clientRepository = clientRepository;
        this.contactRepository = contactRepository;
        this.quoteRepository = quoteRepository;
        this.mailSender = mailSender;
        this.mailSettings = mailSettings;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.reviewUrl = reviewUrl == null ? "" : reviewUrl.trim();
        this.companyName = companyName;
        this.newTx = new TransactionTemplate(transactionManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.businessRepository = businessRepository;
    }

    // ------------------------------------------------------------------ creation on WON

    /**
     * Called by the status-update paths when a lead becomes WON. Creates the client when none exists
     * (idempotent). Never throws and never touches the caller's transaction: the work runs in its own
     * transaction once the status change has committed, so a failure here (logged) cannot undo it.
     */
    public void onLeadWon(Business lead) {
        UUID leadId = lead.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    createClientInOwnTransaction(leadId);
                }
            });
        } else {
            createClientInOwnTransaction(leadId);
        }
    }

    private void createClientInOwnTransaction(UUID leadId) {
        try {
            newTx.executeWithoutResult(status -> businessRepository.findById(leadId).ifPresent(this::ensureClient));
        } catch (RuntimeException e) {
            log.warn("Could not create the client of lead {}: {}", leadId, e.getMessage());
        }
    }

    /**
     * A quote was just accepted for a lead that already has a client: the client takes the quote's
     * service, frequency and price. When there is no client yet, the WON hook creates it from the quote.
     */
    @Transactional
    public void refreshFromAcceptedQuote(UUID businessId) {
        clientRepository.findByBusinessId(businessId).ifPresent(c -> {
            applyAcceptedQuote(businessId, c);
            clientRepository.save(c);
        });
    }

    Client ensureClient(Business lead) {
        if (clientRepository.existsByBusinessId(lead.getId())) {
            return null;
        }
        Client c = new Client();
        c.setBusiness(lead);
        c.setStartDate(LocalDate.now(clock));
        c.setService(DEFAULT_SERVICE);
        c.setFrequency("one-time");

        String wanted = QualificationService.text(lead.getQualification(), "frequencyWanted");
        if (wanted != null && Client.FREQUENCIES.contains(wanted)) {
            c.setFrequency(wanted);
        }
        c.setPricePerVisit(perVisitFromYearlyValue(lead.getEstimatedValue(), c.getFrequency()));
        applyAcceptedQuote(lead.getId(), c);
        c = clientRepository.saveAndFlush(c);
        log.info("Client created for won lead {} ({}, {})", lead.getId(), c.getService(), c.getFrequency());
        return c;
    }

    /** The CRM's estimated value is a yearly amount; a one-time job keeps the whole amount. */
    static BigDecimal perVisitFromYearlyValue(BigDecimal yearly, String frequency) {
        if (yearly == null) {
            return BigDecimal.ZERO;
        }
        int visitsPerYear = switch (frequency == null ? "" : frequency) {
            case "weekly" -> 52;
            case "bi-weekly" -> 26;
            case "monthly" -> 12;
            default -> 1;
        };
        return yearly.divide(BigDecimal.valueOf(visitsPerYear), 2, RoundingMode.HALF_UP);
    }

    /** Takes service, frequency and price per visit from the latest accepted quote of the lead (V15), if any. */
    private void applyAcceptedQuote(UUID businessId, Client c) {
        try {
            Optional<Quote> accepted = quoteRepository
                    .findFirstByBusinessIdAndStatusOrderByAcceptedAtDesc(businessId, Quote.ACCEPTED);
            if (accepted.isEmpty()) {
                return;
            }
            Quote quote = accepted.get();
            if (quote.getSubtotal() != null) {
                c.setPricePerVisit(quote.getSubtotal().setScale(2, RoundingMode.HALF_UP));
            }
            Map<String, Object> input = quote.getInput();
            if (input != null) {
                Object service = input.get("service");
                if (service instanceof String s && !s.isBlank()) {
                    c.setService(PricingService.SERVICE_LABELS_FR.getOrDefault(s, s));
                }
                Object frequency = input.get("frequency");
                if (frequency instanceof String f && Client.FREQUENCIES.contains(f)) {
                    c.setFrequency(f);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Accepted quote of lead {} could not be read: {}", businessId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<ClientDTO> list(String status, UUID businessId) {
        String normalized = status == null || status.isBlank() ? null : status.trim().toLowerCase(Locale.ROOT);
        if (normalized != null && !Client.STATUSES.contains(normalized)) {
            throw ApiException.badRequest("status must be one of " + Client.STATUSES);
        }
        return clientRepository.search(normalized, businessId).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public ClientDTO get(UUID id) {
        return toDto(find(id));
    }

    @Transactional(readOnly = true)
    public ClientsSummary summary() {
        LocalDate today = LocalDate.now(clock);
        List<Client> all = clientRepository.search("active", null);
        BigDecimal mrr = BigDecimal.ZERO;
        long renewals = 0;
        long reviews = 0;
        for (Client c : all) {
            mrr = mrr.add(monthlyValue(c));
            // overdue renewals (end date already passed, still active) count too
            if (c.getContractEndDate() != null && !c.getContractEndDate().isAfter(today.plusDays(RENEWAL_WINDOW_DAYS))) {
                renewals++;
            }
            if (c.getFirstCleaningDoneAt() != null && c.getReviewRequestedAt() == null) {
                reviews++;
            }
        }
        return new ClientsSummary(all.size(), mrr.setScale(2, RoundingMode.HALF_UP), renewals, reviews);
    }

    public ReviewConfig reviewConfig() {
        return new ReviewConfig(!reviewUrl.isEmpty(), smtpConfigured());
    }

    /** Monthly value of one client: weekly x52/12, bi-weekly x26/12, monthly x1, one-time 0. */
    static BigDecimal monthlyValue(Client c) {
        BigDecimal price = c.getPricePerVisit() == null ? BigDecimal.ZERO : c.getPricePerVisit();
        BigDecimal visits = switch (c.getFrequency() == null ? "" : c.getFrequency()) {
            case "weekly" -> BigDecimal.valueOf(52);
            case "bi-weekly" -> BigDecimal.valueOf(26);
            case "monthly" -> BigDecimal.valueOf(12);
            default -> BigDecimal.ZERO;
        };
        return price.multiply(visits).divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ edits

    /** PATCH semantics: keys present are set (null clears the optional ones), others untouched. */
    @Transactional
    public ClientDTO patch(UUID id, Map<String, Object> patch) {
        Client c = find(id);
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            Object v = e.getValue();
            switch (e.getKey()) {
                case "service" -> {
                    String s = v == null ? "" : v.toString().strip();
                    if (s.isEmpty() || s.length() > 200) throw ApiException.badRequest("service est requis (200 caractères max)");
                    c.setService(s);
                }
                case "frequency" -> {
                    String f = v == null ? "" : v.toString().strip();
                    if (!Client.FREQUENCIES.contains(f)) throw ApiException.badRequest("frequency must be one of " + Client.FREQUENCIES);
                    c.setFrequency(f);
                }
                case "pricePerVisit" -> {
                    BigDecimal p = decimal(v);
                    if (p == null || p.signum() < 0 || p.compareTo(new BigDecimal("99999999")) > 0) {
                        throw ApiException.badRequest("pricePerVisit must be a number between 0 and 99999999");
                    }
                    c.setPricePerVisit(p.setScale(2, RoundingMode.HALF_UP));
                }
                case "startDate" -> {
                    LocalDate d = date(v, "startDate");
                    if (d == null) throw ApiException.badRequest("startDate is required");
                    c.setStartDate(d);
                }
                case "contractEndDate" -> c.setContractEndDate(date(v, "contractEndDate"));
                case "nextVisitAt" -> c.setNextVisitAt(dateTime(v, "nextVisitAt"));
                case "status" -> {
                    String s = v == null ? "" : v.toString().strip();
                    if (!Client.STATUSES.contains(s)) throw ApiException.badRequest("status must be one of " + Client.STATUSES);
                    c.setStatus(s);
                }
                case "notes" -> {
                    String n = v == null ? null : v.toString().strip();
                    if (n != null && n.length() > 5000) throw ApiException.badRequest("notes must not exceed 5000 characters");
                    c.setNotes(n == null || n.isEmpty() ? null : n);
                }
                default -> throw ApiException.badRequest("Champ inconnu : " + e.getKey());
            }
        }
        return toDto(clientRepository.saveAndFlush(c));
    }

    @Transactional
    public void delete(UUID id) {
        clientRepository.delete(find(id));
    }

    @Transactional
    public ClientDTO firstCleaningDone(UUID id, User user) {
        Client c = find(id);
        if (c.getFirstCleaningDoneAt() == null) {
            LocalDateTime now = now();
            c.setFirstCleaningDoneAt(now);
            c = clientRepository.saveAndFlush(c);
            note(c.getBusiness(), user, "CLIENT_FIRST_CLEANING", "Premier ménage effectué", now);
        }
        return toDto(c);
    }

    // ------------------------------------------------------------------ Google review request

    @Transactional
    public ReviewResult requestReview(UUID id, String channel, User user) {
        String ch = channel == null ? "" : channel.trim().toLowerCase(Locale.ROOT);
        if (!ch.equals("email") && !ch.equals("link")) {
            throw ApiException.badRequest("channel must be email or link");
        }
        if (reviewUrl.isEmpty()) {
            throw ApiException.serviceUnavailable("Lien d'avis Google non configuré (définir GOOGLE_REVIEW_URL)");
        }
        Client c = find(id);
        Business lead = c.getBusiness();
        boolean english = "en".equals(QualificationService.text(lead.getQualification(), "language"));
        String subject = english ? "Thank you from " + companyName : "Merci de votre confiance - " + companyName;
        String message = reviewMessage(lead, english);
        LocalDateTime now = now();

        if (ch.equals("email")) {
            JavaMailSender sender = smtpConfigured() ? mailSender.getIfAvailable() : null;
            if (sender == null) {
                throw ApiException.serviceUnavailable("Courriel non configuré (définir SMTP_HOST, SMTP_PORT, SMTP_USERNAME, SMTP_PASSWORD)");
            }
            String to = recipient(lead);
            if (to == null) {
                throw ApiException.badRequest("Aucune adresse courriel pour ce client");
            }
            rateLimiter.acquire(user.getId());
            try {
                MimeMessage mime = sender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(mime, false, StandardCharsets.UTF_8.name());
                helper.setFrom(mailSettings.effectiveFrom(), mailSettings.getFromName());
                helper.setTo(to);
                helper.setSubject(subject);
                helper.setText(message, false);
                sender.send(mime);
            } catch (MessagingException | UnsupportedEncodingException | MailException e) {
                log.error("Review request email for client {} failed ({})", c.getId(), e.getClass().getSimpleName());
                throw ApiException.badGateway("Le courriel n'a pas pu être envoyé");
            }
            Contact contact = new Contact();
            contact.setBusiness(lead);
            contact.setUser(user);
            contact.setContactType(Contact.ContactType.EMAIL);
            contact.setContactDate(now);
            contact.setContactStatus("EMAIL_SENT");
            contact.setNotes(subject + "\n\n" + message);
            contactRepository.save(contact);
        } else {
            note(lead, user, "REVIEW_REQUEST", "Lien d'avis Google copié pour envoi manuel", now);
        }
        c.setReviewRequestedAt(now);
        clientRepository.saveAndFlush(c);
        return new ReviewResult(ch, reviewUrl, message, now);
    }

    private String reviewMessage(Business lead, boolean english) {
        String who = QualificationService.text(lead.getQualification(), "decisionMaker");
        if (english) {
            return (who != null ? "Hello " + who + ",\n\n" : "Hello,\n\n")
                    + "Thank you for trusting " + companyName + ". If you are happy with our service, a short Google review "
                    + "would help us a lot: " + reviewUrl + "\n\nThe " + companyName + " team";
        }
        return (who != null ? "Bonjour " + who + ",\n\n" : "Bonjour,\n\n")
                + "Merci de votre confiance. Si notre service vous satisfait, un court avis Google nous aiderait "
                + "beaucoup : " + reviewUrl + "\n\nL'équipe " + companyName;
    }

    private static String recipient(Business lead) {
        for (String candidate : new String[]{lead.getEmail(), QualificationService.text(lead.getQualification(), "email")}) {
            if (candidate != null && EMAIL.matcher(candidate.trim()).matches()) {
                return candidate.trim();
            }
        }
        return null;
    }

    private boolean smtpConfigured() {
        return mailSettings.isConfigured() && mailSender.getIfAvailable() != null;
    }

    // ------------------------------------------------------------------ notifications

    /** contract_renewal, review_request_due and visit_due for leads assigned to the user or unassigned. */
    @Transactional(readOnly = true)
    public List<NotificationDTO> notificationsFor(User user) {
        LocalDateTime now = now();
        LocalDate today = now.toLocalDate();
        List<NotificationDTO> out = new ArrayList<>();

        for (Client c : clientRepository.findRenewals(LocalDate.EPOCH, today.plusDays(RENEWAL_WINDOW_DAYS), user.getId())) {
            long days = ChronoUnit.DAYS.between(today, c.getContractEndDate());
            String when = days < 0 ? "Contrat échu depuis " + (-days) + " jour" + (days < -1 ? "s" : "")
                    : "Contrat à renouveler " + (days == 0 ? "aujourd'hui" : "dans " + days + " jour" + (days > 1 ? "s" : ""));
            out.add(new NotificationDTO("contract_renewal:" + c.getId(), NotificationDTO.CONTRACT_RENEWAL,
                    c.getBusiness().getBusinessName(),
                    when + " (" + DAY.format(c.getContractEndDate()) + ")",
                    c.getBusiness().getId(), null, c.getContractEndDate().atStartOfDay()));
        }
        for (Client c : clientRepository.findReviewDue(now.minus(REVIEW_DELAY), user.getId())) {
            out.add(new NotificationDTO("review_request_due:" + c.getId(), NotificationDTO.REVIEW_REQUEST_DUE,
                    c.getBusiness().getBusinessName(),
                    "Premier ménage fait le " + DAY.format(c.getFirstCleaningDoneAt()) + " : demander un avis Google",
                    c.getBusiness().getId(), null, c.getFirstCleaningDoneAt().plus(REVIEW_DELAY)));
        }
        for (Client c : clientRepository.findVisitsBetween(today.atStartOfDay(), today.plusDays(1).atStartOfDay(), user.getId())) {
            out.add(new NotificationDTO("visit_due:" + c.getId(), NotificationDTO.VISIT_DUE,
                    c.getBusiness().getBusinessName(),
                    "Visite prévue aujourd'hui à " + c.getNextVisitAt().format(HOUR) + " · " + c.getService(),
                    c.getBusiness().getId(), null, c.getNextVisitAt()));
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private Client find(UUID id) {
        return clientRepository.findWithBusiness(id).orElseThrow(() -> ApiException.notFound("Client introuvable : " + id));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    private void note(Business lead, User user, String status, String text, LocalDateTime at) {
        Contact n = new Contact();
        n.setBusiness(lead);
        n.setUser(user);
        n.setContactType(Contact.ContactType.NOTE);
        n.setContactDate(at);
        n.setContactStatus(status);
        n.setNotes(text);
        contactRepository.save(n);
    }

    ClientDTO toDto(Client c) {
        Business b = c.getBusiness();
        return new ClientDTO(c.getId(), b.getId(), b.getBusinessName(), b.getAddressCity(), b.getPhone(), b.getEmail(),
                c.getService(), c.getFrequency(), c.getPricePerVisit(), monthlyValue(c), c.getStartDate(),
                c.getContractEndDate(), c.getNextVisitAt(), c.getStatus(), c.getFirstCleaningDoneAt(),
                c.getReviewRequestedAt(), c.getNotes(), c.getCreatedAt(), c.getUpdatedAt());
    }

    private static BigDecimal decimal(Object v) {
        if (v == null) return null;
        try {
            return new BigDecimal(v.toString().strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate date(Object v, String field) {
        if (v == null || v.toString().isBlank()) return null;
        try {
            return LocalDate.parse(v.toString().strip());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(field + " must be a date YYYY-MM-DD");
        }
    }

    private static LocalDateTime dateTime(Object v, String field) {
        if (v == null || v.toString().isBlank()) return null;
        try {
            return LocalDateTime.parse(v.toString().strip());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(field + " must be a date-time YYYY-MM-DDTHH:mm");
        }
    }
}
