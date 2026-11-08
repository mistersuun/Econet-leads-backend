package com.econet.leads.service;

import com.econet.leads.dto.BusinessDTO;
import com.econet.leads.dto.ClaimDTO;
import com.econet.leads.dto.DashboardDTOs.TodayCounts;
import com.econet.leads.dto.LeadStatusUpdateRequest;
import com.econet.leads.dto.LogCallRequest;
import com.econet.leads.dto.LogCallResponse;
import com.econet.leads.dto.SnoozeRequest;
import com.econet.leads.exception.ApiException;
import com.econet.leads.mapper.DtoMapper;
import com.econet.leads.model.Business;
import com.econet.leads.model.CallOutcome;
import com.econet.leads.model.Contact;
import com.econet.leads.model.LeadStatus;
import com.econet.leads.model.User;
import com.econet.leads.realtime.ClaimRegistry;
import com.econet.leads.realtime.PresenceService;
import com.econet.leads.realtime.SseEventService;
import com.econet.leads.repository.BusinessRepository;
import com.econet.leads.repository.ContactRepository;
import com.econet.leads.repository.UserRepository;
import com.econet.leads.util.PhoneFormatter;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Calling workflow: call queue, claims, call logging with pipeline status transitions and
 * follow-up scheduling, snoozing and manual status changes. All times are America/Montreal
 * wall-clock time (the injected Clock is in that zone, see TimeConfig).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeadService {

    public static final String EVENT_CALL_LOGGED = "call.logged";
    public static final String EVENT_LEAD_UPDATED = "lead.updated";
    public static final String EVENT_TARGET_PROGRESS = "target.progress";

    /** Default time of day for automatically scheduled follow-ups. */
    static final LocalTime FOLLOW_UP_TIME = LocalTime.of(10, 0);
    static final int MAX_QUEUE_SIZE = 100;
    static final Set<CallOutcome> SNOOZABLE_OUTCOMES = EnumSet.of(CallOutcome.NO_ANSWER, CallOutcome.VOICEMAIL, CallOutcome.CALLBACK);
    static final Set<LeadStatus> SNOOZABLE_STATUSES = EnumSet.of(LeadStatus.NEW, LeadStatus.CONTACTED, LeadStatus.INTERESTED, LeadStatus.QUOTE_SENT);
    private static final DateTimeFormatter SNOOZE_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy 'à' HH:mm", Locale.CANADA_FRENCH);

    private final BusinessRepository businessRepository;
    private final ContactRepository contactRepository;
    private final UserRepository userRepository;
    private final DataQualityService dataQualityService;
    private final Clock clock;
    private final ClaimRegistry claimRegistry;
    private final PresenceService presenceService;
    private final SseEventService events;
    private final PriorityScoreService priorityScoreService;
    private final QualificationService qualificationService;
    private final DashboardService dashboardService;

    // ------------------------------------------------------------------ queue

    /** Optional "session mode" filters of GET /api/leads/queue. */
    public record QueueFilters(List<String> businessTypes, List<String> cities, List<String> sources,
                               Integer minQualityScore, boolean followUpsOnly, boolean newOnly) {
        public static final QueueFilters NONE = new QueueFilters(null, null, null, null, false, false);
    }

    /**
     * Next leads to call for this user:
     * 1) follow-ups due (nextFollowUpAt <= now) on open leads, oldest first;
     * 2) NEW leads with a phone by priority score (quality blended with the observed conversion of
     *    the business type), then oldest.
     * Leads that are WON/LOST/DO_NOT_CALL, have no phone, are assigned to another user or are
     * currently claimed by another user are excluded.
     */
    @Transactional(readOnly = true)
    public List<BusinessDTO> getQueue(UUID userId, int size, QueueFilters filters) {
        int limit = Math.max(1, Math.min(size, MAX_QUEUE_SIZE));
        QueueFilters f = filters == null ? QueueFilters.NONE : filters;
        LocalDateTime now = LocalDateTime.now(clock);
        Set<UUID> claimedByOthers = claimRegistry.leadsClaimedByOthers(userId);
        Map<String, Double> rates = priorityScoreService.ratesByType();

        List<Business> result = new ArrayList<>();
        if (!f.newOnly()) {
            result.addAll(fetch(followUpsDue(now, userId, f), limit, claimedByOthers));
        }
        if (!f.followUpsOnly() && result.size() < limit) {
            result.addAll(fetch(newLeads(now, userId, f, rates), limit - result.size(), claimedByOthers));
        }
        return result.stream().map(b -> decorate(DtoMapper.toDto(b), rates)).toList();
    }

    @Transactional(readOnly = true)
    public List<BusinessDTO> getQueue(UUID userId, int size) {
        return getQueue(userId, size, QueueFilters.NONE);
    }

    /** Fetches a page large enough to drop leads claimed by others and still fill {@code limit}. */
    private List<Business> fetch(Specification<Business> spec, int limit, Set<UUID> claimedByOthers) {
        List<Business> page = businessRepository.findAll(spec, PageRequest.of(0, limit + claimedByOthers.size())).getContent();
        List<Business> kept = new ArrayList<>();
        for (Business b : page) {
            if (!claimedByOthers.contains(b.getId())) {
                kept.add(b);
                if (kept.size() == limit) break;
            }
        }
        return kept;
    }

    private static Specification<Business> followUpsDue(LocalDateTime now, UUID userId, QueueFilters f) {
        return (root, query, cb) -> {
            List<Predicate> p = common(root, cb, userId, f);
            p.add(cb.isNotNull(root.get("nextFollowUpAt")));
            p.add(cb.lessThanOrEqualTo(root.get("nextFollowUpAt"), now));
            p.add(cb.not(root.get("leadStatus").in(LeadStatus.TERMINAL)));
            if (!Long.class.equals(query.getResultType())) {
                // hot statuses first (a fresh website request is INTERESTED, due now), then oldest due
                Expression<Integer> hot = cb.<Integer>selectCase()
                        .when(root.get("leadStatus").in(LeadStatus.INTERESTED, LeadStatus.QUOTE_SENT), 0)
                        .otherwise(1);
                query.orderBy(cb.asc(hot), cb.asc(root.get("nextFollowUpAt")), cb.asc(root.get("id")));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
    }

    private static Specification<Business> newLeads(LocalDateTime now, UUID userId, QueueFilters f, Map<String, Double> rates) {
        return (root, query, cb) -> {
            List<Predicate> p = common(root, cb, userId, f);
            p.add(cb.equal(root.get("leadStatus"), LeadStatus.NEW));
            p.add(cb.or(cb.isNull(root.get("nextFollowUpAt")), cb.greaterThan(root.get("nextFollowUpAt"), now)));
            if (!Long.class.equals(query.getResultType())) {
                query.orderBy(cb.desc(priorityExpression(root, cb, rates)), cb.asc(root.get("createdAt")), cb.asc(root.get("id")));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
    }

    /**
     * priority = 0.5 * quality + 50 * rate(businessType), as a SQL expression so the ORDER BY runs
     * in the database (a CASE over the business types with history, the smoothing prior otherwise).
     */
    static Expression<Double> priorityExpression(Root<Business> root, CriteriaBuilder cb, Map<String, Double> rates) {
        Expression<Double> quality = cb.prod(cb.toDouble(cb.coalesce(root.<Integer>get("dataQualityScore"), 0)), 0.5);
        if (rates.isEmpty()) {
            return cb.sum(quality, cb.literal(50.0 * PriorityScoreService.DEFAULT_RATE));
        }
        CriteriaBuilder.SimpleCase<String, Double> bonus = cb.selectCase(root.get("businessType"));
        for (Map.Entry<String, Double> e : rates.entrySet()) {
            bonus = bonus.when(e.getKey(), 50.0 * e.getValue());
        }
        return cb.sum(quality, bonus.otherwise(50.0 * PriorityScoreService.DEFAULT_RATE));
    }

    private static List<Predicate> common(Root<Business> root, CriteriaBuilder cb, UUID userId, QueueFilters f) {
        List<Predicate> p = new ArrayList<>();
        p.add(cb.isNotNull(root.get("phone")));
        p.add(cb.notEqual(root.get("phone"), ""));
        p.add(cb.or(cb.isNull(root.get("assignedTo")), cb.equal(root.get("assignedTo").get("id"), userId)));
        if (hasValues(f.businessTypes())) {
            p.add(root.get("businessType").in(clean(f.businessTypes())));
        }
        if (hasValues(f.cities())) {
            p.add(cb.lower(root.get("addressCity")).in(clean(f.cities()).stream().map(c -> c.toLowerCase(Locale.ROOT)).toList()));
        }
        if (hasValues(f.sources())) {
            p.add(root.get("dataSource").in(clean(f.sources())));
        }
        if (f.minQualityScore() != null) {
            p.add(cb.greaterThanOrEqualTo(root.get("dataQualityScore"), f.minQualityScore()));
        }
        return p;
    }

    private static boolean hasValues(List<String> values) {
        return values != null && values.stream().anyMatch(v -> v != null && !v.isBlank());
    }

    private static List<String> clean(List<String> values) {
        return values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).toList();
    }

    // ------------------------------------------------------------------ claims

    public ClaimDTO claim(UUID businessId, User user) {
        Business lead = businessRepository.findById(businessId)
                .orElseThrow(() -> ApiException.notFound("Lead not found: " + businessId));
        ClaimRegistry.Claim claim = claimRegistry.claim(lead.getId(), lead.getBusinessName(), user.getId(), user.getUsername());
        presenceService.touch(user.getId());
        return ClaimDTO.from(claim);
    }

    /** Releases the user's own claim (no-op when they hold none). */
    public void releaseClaim(UUID businessId, User user) {
        claimRegistry.release(businessId, user.getId());
        presenceService.touch(user.getId());
    }

    // ------------------------------------------------------------------ call logging

    @Transactional
    public LogCallResponse logCall(UUID businessId, LogCallRequest request, UUID callerId) {
        Business lead = businessRepository.findByIdForUpdate(businessId)
                .orElseThrow(() -> ApiException.notFound("Lead not found: " + businessId));
        User caller = userRepository.findById(callerId)
                .orElseThrow(() -> ApiException.notFound("User not found: " + callerId));

        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        CallOutcome outcome = request.getOutcome();
        if (request.getSnoozeUntil() != null && !SNOOZABLE_OUTCOMES.contains(outcome)) {
            throw ApiException.badRequest("snoozeUntil is only allowed with outcome NO_ANSWER, VOICEMAIL or CALLBACK");
        }

        LeadStatus newStatus = nextStatus(lead.getLeadStatus(), outcome);
        LocalDateTime followUp;
        if (request.getSnoozeUntil() != null) {
            followUp = request.getSnoozeUntil();
        } else if (request.getNextFollowUpAt() != null) {
            followUp = request.getNextFollowUpAt();
        } else {
            followUp = defaultFollowUp(outcome, newStatus, now);
        }

        // The new call supersedes follow-ups planned on earlier contacts
        contactRepository.clearNextActionDates(lead.getId());

        Contact contact = new Contact();
        contact.setBusiness(lead);
        contact.setUser(caller);
        contact.setContactType(Contact.ContactType.APPEL);
        contact.setContactDate(now);
        contact.setContactStatus(outcome.name());
        contact.setOutcome(outcome.toContactOutcome());
        contact.setContactPerson(trimToNull(request.getContactPerson()));
        contact.setNotes(trimToNull(request.getNotes()));
        contact.setDurationSeconds(request.getDurationSeconds());
        contact.setNextActionDate(followUp);
        contact.setNextAction(followUp != null ? "Rappel" : null);
        contact = contactRepository.saveAndFlush(contact); // flush so createdAt is populated in the response

        lead.setLeadStatus(newStatus);
        lead.setLastContactedAt(now);
        lead.setContactCount((lead.getContactCount() != null ? lead.getContactCount() : 0) + 1);
        lead.setNextFollowUpAt(followUp);
        if (lead.getAssignedTo() == null) {
            lead.setAssignedTo(caller);
        }
        if (request.getEstimatedValue() != null) {
            lead.setEstimatedValue(request.getEstimatedValue());
        }
        if (request.getQualification() != null && !request.getQualification().isEmpty()) {
            lead.setQualification(qualificationService.merge(lead.getQualification(), request.getQualification()));
        }
        lead = businessRepository.saveAndFlush(lead); // flush so updatedAt is current in the response

        claimRegistry.release(lead.getId(), null);
        presenceService.touch(callerId);
        publishCallLogged(contact, lead, caller);
        publishLeadUpdated(lead);
        publishTargetProgress(caller);

        log.info("Call logged on lead {} by {}: {} -> status {}", lead.getId(), caller.getUsername(), outcome, newStatus);
        return new LogCallResponse(decorate(DtoMapper.toDto(lead)), DtoMapper.toDto(contact));
    }

    // ------------------------------------------------------------------ snooze

    /**
     * Pushes the follow-up to {@code until} without changing the status, records a
     * "Reporté au …" note and releases the caller's claim. Allowed on NEW, CONTACTED, INTERESTED,
     * QUOTE_SENT.
     */
    @Transactional
    public BusinessDTO snooze(UUID businessId, SnoozeRequest request, UUID userId) {
        Business lead = businessRepository.findByIdForUpdate(businessId)
                .orElseThrow(() -> ApiException.notFound("Lead not found: " + businessId));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found: " + userId));
        if (!SNOOZABLE_STATUSES.contains(lead.getLeadStatus())) {
            throw ApiException.badRequest("Cannot snooze a lead in status " + lead.getLeadStatus()
                    + " (allowed: NEW, CONTACTED, INTERESTED, QUOTE_SENT)");
        }
        LocalDateTime until = request.getUntil().truncatedTo(ChronoUnit.MINUTES);
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        if (!until.isAfter(now)) {
            throw ApiException.badRequest("until must be in the future");
        }

        contactRepository.clearNextActionDates(lead.getId());
        lead.setNextFollowUpAt(until);
        lead = businessRepository.saveAndFlush(lead);

        Contact note = new Contact();
        note.setBusiness(lead);
        note.setUser(user);
        note.setContactType(Contact.ContactType.NOTE);
        note.setContactDate(now);
        note.setContactStatus("SNOOZED");
        String text = "Reporté au " + SNOOZE_DATE.format(until);
        String reason = trimToNull(request.getReason());
        note.setNotes(reason == null ? text : text + "\n" + reason);
        note.setNextAction("Rappel");
        note.setNextActionDate(until);
        contactRepository.save(note);

        claimRegistry.release(lead.getId(), null);
        presenceService.touch(userId);
        publishLeadUpdated(lead);
        log.info("Lead {} snoozed until {} by {}", lead.getId(), until, user.getUsername());
        return decorate(DtoMapper.toDto(lead));
    }

    // ------------------------------------------------------------------ manual status change

    /**
     * Sets the pipeline status. Terminal statuses clear the follow-up. The change (and optional
     * note) is recorded in the lead history as a NOTE contact; changes to INTERESTED, QUOTE_SENT,
     * WON, LOST and DO_NOT_CALL carry the matching outcome so dashboard counters see them.
     */
    @Transactional
    public BusinessDTO updateStatus(UUID businessId, LeadStatusUpdateRequest request, UUID userId) {
        Business lead = businessRepository.findByIdForUpdate(businessId)
                .orElseThrow(() -> ApiException.notFound("Lead not found: " + businessId));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found: " + userId));

        LeadStatus previous = lead.getLeadStatus();
        LeadStatus next = request.getStatus();
        String note = trimToNull(request.getNote());

        if (previous == next && note == null) {
            return decorate(DtoMapper.toDto(lead));
        }

        lead.setLeadStatus(next);
        if (next.isTerminal()) {
            lead.setNextFollowUpAt(null);
            contactRepository.clearNextActionDates(lead.getId());
        }
        lead = businessRepository.saveAndFlush(lead);

        Contact history = new Contact();
        history.setBusiness(lead);
        history.setUser(user);
        history.setContactType(Contact.ContactType.NOTE);
        history.setContactDate(LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
        history.setContactStatus("STATUS_CHANGE");
        history.setOutcome(previous != next ? outcomeForStatus(next) : null);
        String text = previous != next ? "Statut: " + previous + " → " + next : null;
        if (note != null) {
            text = text == null ? note : text + "\n" + note;
        }
        history.setNotes(text);
        contactRepository.save(history);

        claimRegistry.release(lead.getId(), null);
        presenceService.touch(userId);
        publishLeadUpdated(lead);
        return decorate(DtoMapper.toDto(lead));
    }

    // ------------------------------------------------------------------ qualification

    @Transactional(readOnly = true)
    public Map<String, Object> getQualification(UUID businessId) {
        Business lead = businessRepository.findById(businessId)
                .orElseThrow(() -> ApiException.notFound("Lead not found: " + businessId));
        return lead.getQualification() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(lead.getQualification());
    }

    /** PATCH semantics: keys present are set (null clears), others untouched. */
    @Transactional
    public Map<String, Object> patchQualification(UUID businessId, Map<String, Object> patch, UUID userId) {
        Business lead = businessRepository.findByIdForUpdate(businessId)
                .orElseThrow(() -> ApiException.notFound("Lead not found: " + businessId));
        lead.setQualification(qualificationService.merge(lead.getQualification(), patch));
        lead = businessRepository.saveAndFlush(lead);
        presenceService.touch(userId);
        return new LinkedHashMap<>(lead.getQualification());
    }

    // ------------------------------------------------------------------ phone enrichment

    /**
     * Adds (or replaces) the phone of a lead, typically one imported without a number (permits,
     * business register) so it can enter the call queue. Accepts 10 NANP digits with an optional
     * leading +1 / 1; anything else is a 400. Recomputes the quality score and records a NOTE
     * contact "Numéro ajouté" in the lead history.
     */
    @Transactional
    public BusinessDTO updatePhone(UUID businessId, String rawPhone, UUID userId) {
        String phone = trimToNull(rawPhone);
        if (phone == null || !isValidNanpPhone(phone)) {
            throw ApiException.badRequest("Numéro de téléphone invalide: 10 chiffres attendus (ex. 514 555-0123), +1 optionnel");
        }
        Business lead = businessRepository.findByIdForUpdate(businessId)
                .orElseThrow(() -> ApiException.notFound("Lead not found: " + businessId));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found: " + userId));

        String previous = lead.getPhone();
        lead.setPhone(PhoneFormatter.format(phone));
        lead.setPhoneNormalized(PhoneFormatter.normalize(phone));
        lead.setDataQualityScore(dataQualityService.calculateQualityScore(lead));
        lead = businessRepository.saveAndFlush(lead);

        Contact history = new Contact();
        history.setBusiness(lead);
        history.setUser(user);
        history.setContactType(Contact.ContactType.NOTE);
        history.setContactDate(LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
        history.setContactStatus("PHONE_ADDED");
        history.setNotes(previous == null || previous.isBlank()
                ? "Numéro ajouté: " + lead.getPhone()
                : "Numéro ajouté: " + lead.getPhone() + " (remplace " + previous + ")");
        contactRepository.save(history);

        log.info("Phone set on lead {} by {}", lead.getId(), user.getUsername());
        return decorate(DtoMapper.toDto(lead));
    }

    /**
     * 10 digits (NANP: area code and exchange do not start with 0 or 1), optionally preceded by
     * +1 / 1. Separators (spaces, dashes, dots, parentheses) are ignored; letters are rejected.
     */
    static boolean isValidNanpPhone(String phone) {
        if (!phone.matches("[0-9+()\\-. ]+")) {
            return false;
        }
        String digits = phone.replaceAll("[^0-9]", "");
        if (phone.trim().startsWith("+") && !digits.startsWith("1")) {
            return false;
        }
        if (digits.length() == 11 && digits.startsWith("1")) {
            digits = digits.substring(1);
        }
        return digits.length() == 10
                && digits.charAt(0) >= '2'
                && digits.charAt(3) >= '2';
    }

    // ------------------------------------------------------------------ events

    private void publishCallLogged(Contact contact, Business lead, User caller) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contactId", contact.getId());
        payload.put("leadId", lead.getId());
        payload.put("leadName", lead.getBusinessName());
        payload.put("userId", caller.getId());
        payload.put("username", caller.getUsername());
        payload.put("outcome", contact.getOutcome());
        payload.put("durationSeconds", contact.getDurationSeconds());
        payload.put("at", contact.getContactDate());
        events.publish(EVENT_CALL_LOGGED, payload);
    }

    private void publishLeadUpdated(Business lead) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("leadId", lead.getId());
        payload.put("leadStatus", lead.getLeadStatus());
        payload.put("nextFollowUpAt", lead.getNextFollowUpAt());
        events.publish(EVENT_LEAD_UPDATED, payload);
    }

    private void publishTargetProgress(User caller) {
        TodayCounts counts = dashboardService.getTodayCounts(caller.getId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", caller.getId());
        payload.put("callsToday", counts.callsToday());
        payload.put("conversationsToday", counts.conversationsToday());
        payload.put("target", caller.getDailyCallTarget() != null ? caller.getDailyCallTarget() : User.DEFAULT_DAILY_CALL_TARGET);
        payload.put("conversationTarget", caller.getDailyConversationTarget() != null
                ? caller.getDailyConversationTarget() : User.DEFAULT_DAILY_CONVERSATION_TARGET);
        events.publish(EVENT_TARGET_PROGRESS, payload);
    }

    // ------------------------------------------------------------------ DTO decoration

    private BusinessDTO decorate(BusinessDTO dto) {
        return decorate(dto, priorityScoreService.ratesByType());
    }

    private BusinessDTO decorate(BusinessDTO dto, Map<String, Double> rates) {
        priorityScoreService.decorate(dto, rates);
        claimRegistry.get(dto.getId()).ifPresent(c -> dto.setClaimedBy(new BusinessDTO.ClaimedBy(c.userId(), c.username())));
        return dto;
    }

    // ------------------------------------------------------------------ rules (pure functions)

    /**
     * Status after a call with the given outcome.
     * <ul>
     *   <li>NO_ANSWER / VOICEMAIL / CALLBACK: CONTACTED, but INTERESTED, QUOTE_SENT and WON are kept
     *       (an unanswered call must not move a lead backwards in the pipeline);</li>
     *   <li>INTERESTED, QUOTE_SENT, WON: same status;</li>
     *   <li>NOT_INTERESTED: LOST;</li>
     *   <li>WRONG_NUMBER, DO_NOT_CALL: DO_NOT_CALL.</li>
     * </ul>
     */
    public static LeadStatus nextStatus(LeadStatus current, CallOutcome outcome) {
        return switch (outcome) {
            case NO_ANSWER, VOICEMAIL, CALLBACK ->
                    (current == LeadStatus.INTERESTED || current == LeadStatus.QUOTE_SENT || current == LeadStatus.WON)
                            ? current
                            : LeadStatus.CONTACTED;
            case INTERESTED -> LeadStatus.INTERESTED;
            case QUOTE_SENT -> LeadStatus.QUOTE_SENT;
            case WON -> LeadStatus.WON;
            case NOT_INTERESTED -> LeadStatus.LOST;
            case WRONG_NUMBER, DO_NOT_CALL -> LeadStatus.DO_NOT_CALL;
        };
    }

    /**
     * Follow-up when the caller didn't pick one:
     * <ul>
     *   <li>terminal resulting status (WON, LOST, DO_NOT_CALL): none;</li>
     *   <li>CALLBACK / NO_ANSWER / VOICEMAIL: +2 business days (Mon-Fri) at 10:00;</li>
     *   <li>INTERESTED / QUOTE_SENT: +3 calendar days at 10:00, moved to Monday if that is a weekend.</li>
     * </ul>
     */
    public static LocalDateTime defaultFollowUp(CallOutcome outcome, LeadStatus resultingStatus, LocalDateTime now) {
        if (resultingStatus.isTerminal()) {
            return null;
        }
        LocalDate today = now.toLocalDate();
        return switch (outcome) {
            case CALLBACK, NO_ANSWER, VOICEMAIL -> addBusinessDays(today, 2).atTime(FOLLOW_UP_TIME);
            case INTERESTED, QUOTE_SENT -> nextWeekday(today.plusDays(3)).atTime(FOLLOW_UP_TIME);
            default -> null;
        };
    }

    /** Adds n business days (skipping Saturday and Sunday). */
    public static LocalDate addBusinessDays(LocalDate date, int days) {
        LocalDate result = date;
        int added = 0;
        while (added < days) {
            result = result.plusDays(1);
            if (!isWeekend(result)) {
                added++;
            }
        }
        return result;
    }

    private static LocalDate nextWeekday(LocalDate date) {
        LocalDate result = date;
        while (isWeekend(result)) {
            result = result.plusDays(1);
        }
        return result;
    }

    private static boolean isWeekend(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    static Contact.ContactOutcome outcomeForStatus(LeadStatus status) {
        return switch (status) {
            case INTERESTED -> Contact.ContactOutcome.INTERESTED;
            case QUOTE_SENT -> Contact.ContactOutcome.QUOTE_SENT;
            case WON -> Contact.ContactOutcome.WON;
            case LOST -> Contact.ContactOutcome.NOT_INTERESTED;
            case DO_NOT_CALL -> Contact.ContactOutcome.DO_NOT_CALL;
            case NEW, CONTACTED -> null;
        };
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
