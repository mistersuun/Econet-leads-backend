package com.econet.leads.service;

import com.econet.leads.config.MailSettings;
import com.econet.leads.config.TimeConfig;
import com.econet.leads.dto.BookingDTOs;
import com.econet.leads.exception.ApiException;
import com.econet.leads.model.Availability;
import com.econet.leads.model.Business;
import com.econet.leads.model.BookingLink;
import com.econet.leads.model.Contact;
import com.econet.leads.model.User;
import com.econet.leads.model.Visit;
import com.econet.leads.realtime.SseEventService;
import com.econet.leads.repository.AvailabilityRepository;
import com.econet.leads.repository.BookingLinkRepository;
import com.econet.leads.repository.BusinessRepository;
import com.econet.leads.repository.ContactRepository;
import com.econet.leads.repository.UserRepository;
import com.econet.leads.repository.VisitRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Self-serve visit booking: weekly availability (admin-edited), single-use booking links, free-slot
 * computation (availability minus booked visits, 24 h minimum notice, next 14 days) and the booking
 * itself. All times are America/Montreal wall-clock.
 */
@Service
@Slf4j
public class BookingService {

    public static final String EVENT_VISIT_BOOKED = "visit.booked";
    public static final int LINK_DAYS = 14;
    public static final int HORIZON_DAYS = 14;
    public static final int MIN_NOTICE_HOURS = 24;
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter WHEN_FR = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy 'à' H 'h' mm", Locale.CANADA_FRENCH);
    private static final DateTimeFormatter WHEN_EN = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.CANADA);

    /** A free slot, as offered on the public page. */
    public record Slot(LocalDateTime start, LocalDateTime end) {
    }

    /** What the public booking page needs: the lead's name, whether it can still book, and the slots. */
    public record PublicView(String leadName, String language, LinkState state, LocalDateTime bookedAt, List<Slot> slots) {
    }

    public enum LinkState { OPEN, USED, EXPIRED }

    public record Booked(LocalDateTime startsAt, LocalDateTime endsAt) {
    }

    private final AvailabilityRepository availabilityRepository;
    private final VisitRepository visitRepository;
    private final BookingLinkRepository linkRepository;
    private final BusinessRepository businessRepository;
    private final ContactRepository contactRepository;
    private final UserRepository userRepository;
    private final SseEventService events;
    private final PublicLinkService links;
    private final MailSettings mailSettings;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final String companyName;
    private final Object bookingLock = new Object();

    public BookingService(AvailabilityRepository availabilityRepository, VisitRepository visitRepository,
                          BookingLinkRepository linkRepository, BusinessRepository businessRepository,
                          ContactRepository contactRepository, UserRepository userRepository,
                          SseEventService events, PublicLinkService links, MailSettings mailSettings,
                          ObjectProvider<JavaMailSender> mailSender, Clock clock,
                          PlatformTransactionManager transactionManager,
                          @Value("${app.company.name:EcoNet Propreté}") String companyName) {
        this.availabilityRepository = availabilityRepository;
        this.visitRepository = visitRepository;
        this.linkRepository = linkRepository;
        this.businessRepository = businessRepository;
        this.contactRepository = contactRepository;
        this.userRepository = userRepository;
        this.events = events;
        this.links = links;
        this.mailSettings = mailSettings;
        this.mailSender = mailSender;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.companyName = companyName;
    }

    // ------------------------------------------------------------------ CRM side

    @Transactional
    public BookingDTOs.LinkResponse createLink(UUID leadId, User caller) {
        Business lead = businessRepository.findById(leadId)
                .orElseThrow(() -> ApiException.notFound("Lead introuvable : " + leadId));
        BookingLink link = new BookingLink();
        link.setBusiness(lead);
        link.setCreatedBy(caller);
        link.setToken(PublicLinkService.newToken());
        link.setExpiresAt(LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS).plusDays(LINK_DAYS));
        link = linkRepository.saveAndFlush(link);
        log.info("Booking link created for lead {} by {}", leadId, caller.getUsername());
        return new BookingDTOs.LinkResponse(links.bookingUrl(link.getToken()), link.getExpiresAt());
    }

    @Transactional(readOnly = true)
    public List<BookingDTOs.Visit> visits(LocalDate from, LocalDate to) {
        LocalDate start = from != null ? from : LocalDate.now(clock);
        LocalDate end = to != null ? to : start.plusDays(30);
        if (end.isBefore(start)) {
            throw ApiException.badRequest("'to' must not be before 'from'");
        }
        if (start.plusDays(366).isBefore(end)) {
            throw ApiException.badRequest("La plage ne doit pas dépasser un an");
        }
        return visitRepository.findBetween(start.atStartOfDay(), end.plusDays(1).atStartOfDay()).stream()
                .map(BookingService::toDto).toList();
    }

    @Transactional
    public BookingDTOs.Visit updateVisitStatus(UUID id, String status) {
        String s = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        if (!List.of(Visit.BOOKED, Visit.DONE, Visit.CANCELLED).contains(s)) {
            throw ApiException.badRequest("status must be booked, done or cancelled");
        }
        Visit visit = visitRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Visite introuvable : " + id));
        visit.setStatus(s);
        return toDto(visitRepository.saveAndFlush(visit));
    }

    static BookingDTOs.Visit toDto(Visit v) {
        Business b = v.getBusiness();
        return new BookingDTOs.Visit(v.getId(), b.getId(), b.getBusinessName(), b.getPhone(), b.getFullAddress(),
                v.getStartsAt(), v.getEndsAt(), v.getStatus(), v.getBookedVia());
    }

    @Transactional(readOnly = true)
    public List<BookingDTOs.Window> availability() {
        return availabilityRepository.findAllByOrderByWeekdayAscStartTimeAsc().stream().map(BookingService::toDto).toList();
    }

    /** Replaces every window. Validates ranges and overlaps within a weekday. */
    @Transactional
    public List<BookingDTOs.Window> replaceAvailability(List<BookingDTOs.Window> windows) {
        if (windows == null) {
            throw ApiException.badRequest("Une liste de plages est requise");
        }
        if (windows.size() > 50) {
            throw ApiException.badRequest("At most 50 windows");
        }
        List<Availability> parsed = new ArrayList<>();
        for (BookingDTOs.Window w : windows) {
            Availability a = new Availability();
            if (w.weekday() < 1 || w.weekday() > 7) {
                throw ApiException.badRequest("weekday must be between 1 (Monday) and 7 (Sunday)");
            }
            a.setWeekday(w.weekday());
            try {
                a.setStartTime(LocalTime.parse(w.startTime()));
                a.setEndTime(LocalTime.parse(w.endTime()));
            } catch (DateTimeParseException | NullPointerException e) {
                throw ApiException.badRequest("startTime and endTime must be HH:mm");
            }
            if (!a.getEndTime().isAfter(a.getStartTime())) {
                throw ApiException.badRequest("endTime must be after startTime");
            }
            if (w.slotMinutes() < 15 || w.slotMinutes() > 240) {
                throw ApiException.badRequest("slotMinutes must be between 15 and 240");
            }
            a.setSlotMinutes(w.slotMinutes());
            a.setActive(w.active());
            parsed.add(a);
        }
        for (int i = 0; i < parsed.size(); i++) {
            for (int j = i + 1; j < parsed.size(); j++) {
                Availability x = parsed.get(i);
                Availability y = parsed.get(j);
                if (x.getWeekday().equals(y.getWeekday())
                        && x.getStartTime().isBefore(y.getEndTime()) && y.getStartTime().isBefore(x.getEndTime())) {
                    throw ApiException.badRequest("Windows of the same weekday must not overlap");
                }
            }
        }
        availabilityRepository.deleteAllInBatch();
        availabilityRepository.saveAll(parsed);
        availabilityRepository.flush();
        return availability();
    }

    private static BookingDTOs.Window toDto(Availability a) {
        return new BookingDTOs.Window(a.getId(), a.getWeekday(), a.getStartTime().format(HHMM), a.getEndTime().format(HHMM),
                a.getSlotMinutes(), Boolean.TRUE.equals(a.getActive()));
    }

    // ------------------------------------------------------------------ slots

    /** Free slots over the next {@value #HORIZON_DAYS} days (today included), at least 24 h from now. */
    List<Slot> freeSlots() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime earliest = now.plusHours(MIN_NOTICE_HOURS);
        LocalDate today = now.toLocalDate();
        List<Availability> windows = availabilityRepository.findByActiveTrueOrderByWeekdayAscStartTimeAsc();
        List<Visit> booked = visitRepository.findBookedOverlapping(today.atStartOfDay(), today.plusDays(HORIZON_DAYS + 1).atStartOfDay());
        List<Slot> slots = new ArrayList<>();
        for (int d = 0; d <= HORIZON_DAYS; d++) {
            LocalDate day = today.plusDays(d);
            for (Availability w : windows) {
                if (w.getWeekday() != day.getDayOfWeek().getValue()) continue;
                LocalDateTime start = day.atTime(w.getStartTime());
                LocalDateTime windowEnd = day.atTime(w.getEndTime());
                while (!start.plusMinutes(w.getSlotMinutes()).isAfter(windowEnd)) {
                    LocalDateTime end = start.plusMinutes(w.getSlotMinutes());
                    final LocalDateTime s = start;
                    boolean taken = booked.stream().anyMatch(v -> v.getStartsAt().isBefore(end) && v.getEndsAt().isAfter(s));
                    if (!start.isBefore(earliest) && !taken) {
                        slots.add(new Slot(start, end));
                    }
                    start = end;
                }
            }
        }
        slots.sort((a, b) -> a.start().compareTo(b.start()));
        return slots;
    }

    // ------------------------------------------------------------------ public side

    @Transactional(readOnly = true)
    public PublicView view(String token) {
        BookingLink link = findLink(token, false);
        Business lead = link.getBusiness();
        String language = "en".equals(QualificationService.text(lead.getQualification(), "language")) ? "en" : "fr";
        LinkState state = state(link);
        LocalDateTime bookedAt = null;
        if (state == LinkState.USED && link.getVisitId() != null) {
            bookedAt = visitRepository.findById(link.getVisitId()).map(Visit::getStartsAt).orElse(null);
        }
        List<Slot> slots = state == LinkState.OPEN ? freeSlots() : List.of();
        return new PublicView(lead.getBusinessName(), language, state, bookedAt, slots);
    }

    /** Thrown when the chosen slot is not (or no longer) free. */
    public static class SlotUnavailableException extends RuntimeException {
        public SlotUnavailableException() {
            super("slot unavailable");
        }
    }

    /** Thrown when the link is already used or expired. */
    public static class LinkClosedException extends RuntimeException {
        private final LinkState state;

        public LinkClosedException(LinkState state) {
            super("link " + state);
            this.state = state;
        }

        public LinkState state() {
            return state;
        }
    }

    /** Books the slot ("yyyy-MM-ddTHH:mm", Montreal time). Emails and the SSE event follow the commit. */
    public Booked book(String token, String slot) {
        LocalDateTime start;
        try {
            start = LocalDateTime.parse(slot == null ? "" : slot.strip());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("slot must look like 2026-11-20T10:00");
        }
        start = start.truncatedTo(ChronoUnit.MINUTES);
        final LocalDateTime wanted = start;
        Confirmation done;
        synchronized (bookingLock) {
            done = tx.execute(status -> doBook(token, wanted));
        }
        afterBooked(done);
        return new Booked(done.start(), done.end());
    }

    /** Everything the post-commit side effects need, copied out of the transaction. */
    private record Confirmation(UUID visitId, UUID leadId, String leadName, String leadAddress, String language,
                                String decisionMaker, String prospectEmail, String creatorEmail,
                                LocalDateTime start, LocalDateTime end) {
    }

    private Confirmation doBook(String token, LocalDateTime wanted) {
        BookingLink link = findLink(token, true);
        LinkState state = state(link);
        if (state != LinkState.OPEN) {
            throw new LinkClosedException(state);
        }
        Slot slot = freeSlots().stream().filter(s -> s.start().equals(wanted)).findFirst()
                .orElseThrow(SlotUnavailableException::new);
        Business lead = businessRepository.findByIdForUpdate(link.getBusiness().getId())
                .orElseThrow(() -> ApiException.notFound("Introuvable"));
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);

        Visit visit = new Visit();
        visit.setBusiness(lead);
        visit.setStartsAt(slot.start());
        visit.setEndsAt(slot.end());
        visit.setStatus(Visit.BOOKED);
        visit.setBookedVia(Visit.VIA_LINK);
        visit = visitRepository.saveAndFlush(visit);

        link.setUsedAt(now);
        link.setVisitId(visit.getId());
        linkRepository.saveAndFlush(link);

        contactRepository.clearNextActionDates(lead.getId());
        lead.setNextFollowUpAt(slot.start());
        businessRepository.saveAndFlush(lead);

        Optional<User> actor = Optional.ofNullable(link.getCreatedBy())
                .or(() -> Optional.ofNullable(lead.getAssignedTo()))
                .or(() -> userRepository.findFirstByRoleAndActiveTrueOrderByCreatedAtAsc(User.UserRole.ADMIN));
        actor.ifPresentOrElse(user -> {
            Contact note = new Contact();
            note.setBusiness(lead);
            note.setUser(user);
            note.setContactType(Contact.ContactType.NOTE);
            note.setContactDate(now);
            note.setContactStatus("VISIT_BOOKED");
            note.setNotes("Visite réservée par le client : " + WHEN_FR.format(slot.start()));
            note.setNextAction("Visite");
            note.setNextActionDate(slot.start());
            contactRepository.save(note);
        }, () -> log.warn("Visit {} booked but no user available to record the note", slot.start()));
        return new Confirmation(visit.getId(), lead.getId(), lead.getBusinessName(), lead.getFullAddress(),
                "en".equals(QualificationService.text(lead.getQualification(), "language")) ? "en" : "fr",
                QualificationService.text(lead.getQualification(), "decisionMaker"),
                firstValid(QualificationService.text(lead.getQualification(), "email"), lead.getEmail()),
                link.getCreatedBy() != null ? firstValid(link.getCreatedBy().getEmail()) : null,
                visit.getStartsAt(), visit.getEndsAt());
    }

    /** Side effects after the commit; none of them can fail the booking. */
    private void afterBooked(Confirmation c) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("leadId", c.leadId());
        payload.put("leadName", c.leadName());
        payload.put("startsAt", c.start());
        events.publish(EVENT_VISIT_BOOKED, payload);
        try {
            sendInvites(c);
        } catch (RuntimeException e) {
            log.warn("Visit invite emails failed: {}", e.toString());
        }
    }

    private void sendInvites(Confirmation c) {
        JavaMailSender sender = mailSettings.isConfigured() ? mailSender.getIfAvailable() : null;
        if (sender == null) {
            return;
        }
        boolean en = "en".equals(c.language());
        String when = (en ? WHEN_EN : WHEN_FR).format(c.start());
        long minutes = ChronoUnit.MINUTES.between(c.start(), c.end());
        String ics = IcsBuilder.build(c.visitId() + "@econet-leads",
                "Visite " + companyName + " – " + c.leadName(), c.leadAddress(),
                "Visite de " + companyName + " : " + c.leadName(),
                c.start(), (int) minutes, mailSettings.getFromName(), mailSettings.effectiveFrom(),
                c.decisionMaker(), c.prospectEmail(), clock.instant());
        if (c.prospectEmail() != null) {
            String subject = en ? "Your visit with " + companyName + " is confirmed" : "Votre visite avec " + companyName + " est confirmée";
            String body = en
                    ? "Hello,\n\nYour visit is confirmed for " + when + ".\nThe invitation is attached so you can add it to your calendar.\n\n" + companyName
                    : "Bonjour,\n\nVotre visite est confirmée pour le " + when + ".\nL'invitation est jointe pour l'ajouter à votre calendrier.\n\n" + companyName;
            sendIcs(sender, c.prospectEmail(), subject, body, ics);
        }
        if (c.creatorEmail() != null) {
            String body = "Visite réservée par " + c.leadName() + " : " + WHEN_FR.format(c.start())
                    + (c.leadAddress() != null ? "\n" + c.leadAddress() : "");
            sendIcs(sender, c.creatorEmail(), "Visite réservée – " + c.leadName(), body, ics);
        }
    }

    private void sendIcs(JavaMailSender sender, String to, String subject, String body, String ics) {
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(mailSettings.effectiveFrom(), mailSettings.getFromName());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);
            helper.addAttachment(EmailService.ICS_FILENAME, new ByteArrayResource(ics.getBytes(StandardCharsets.UTF_8)),
                    "text/calendar; charset=UTF-8; method=REQUEST");
            sender.send(message);
        } catch (MessagingException | UnsupportedEncodingException | MailException e) {
            log.warn("Visit invite email failed ({})", e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ helpers

    private BookingLink findLink(String token, boolean forUpdate) {
        if (!PublicLinkService.looksLikeToken(token)) {
            throw ApiException.notFound("Introuvable");
        }
        Optional<BookingLink> link = forUpdate ? linkRepository.findByTokenForUpdate(token) : linkRepository.findByToken(token);
        return link.filter(l -> PublicLinkService.secretsEqual(l.getToken(), token))
                .orElseThrow(() -> ApiException.notFound("Introuvable"));
    }

    private LinkState state(BookingLink link) {
        if (link.getUsedAt() != null) return LinkState.USED;
        if (!link.getExpiresAt().isAfter(LocalDateTime.now(clock))) return LinkState.EXPIRED;
        return LinkState.OPEN;
    }

    private static String firstValid(String... values) {
        for (String v : values) {
            if (v != null && v.trim().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) return v.trim();
        }
        return null;
    }
}
