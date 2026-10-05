package com.econet.leads.service;

import com.econet.leads.dto.BusinessDTO;
import com.econet.leads.dto.LeadStatusUpdateRequest;
import com.econet.leads.dto.LogCallRequest;
import com.econet.leads.dto.LogCallResponse;
import com.econet.leads.exception.ApiException;
import com.econet.leads.mapper.DtoMapper;
import com.econet.leads.model.Business;
import com.econet.leads.model.CallOutcome;
import com.econet.leads.model.Contact;
import com.econet.leads.model.LeadStatus;
import com.econet.leads.model.User;
import com.econet.leads.repository.BusinessRepository;
import com.econet.leads.repository.ContactRepository;
import com.econet.leads.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Calling workflow: call queue, call logging with pipeline status transitions and follow-up
 * scheduling, and manual status changes. All times are America/Montreal wall-clock time (the
 * injected Clock is in that zone, see TimeConfig).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeadService {

    /** Default time of day for automatically scheduled follow-ups. */
    static final LocalTime FOLLOW_UP_TIME = LocalTime.of(10, 0);
    static final int MAX_QUEUE_SIZE = 100;

    private final BusinessRepository businessRepository;
    private final ContactRepository contactRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    // ------------------------------------------------------------------ queue

    /**
     * Next leads to call for this user:
     * 1) follow-ups due (nextFollowUpAt <= now) on open leads, oldest first;
     * 2) NEW leads with a phone, highest data quality first, then oldest.
     * Leads that are WON/LOST/DO_NOT_CALL, have no phone, or are assigned to another user are excluded.
     */
    @Transactional(readOnly = true)
    public List<BusinessDTO> getQueue(UUID userId, int size) {
        int limit = Math.max(1, Math.min(size, MAX_QUEUE_SIZE));
        LocalDateTime now = LocalDateTime.now(clock);

        List<Business> result = new ArrayList<>(businessRepository.findQueueFollowUpsDue(
                now, LeadStatus.TERMINAL, userId, PageRequest.of(0, limit)));
        if (result.size() < limit) {
            result.addAll(businessRepository.findQueueNewLeads(now, userId, PageRequest.of(0, limit - result.size())));
        }
        return result.stream().map(DtoMapper::toDto).toList();
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

        LeadStatus newStatus = nextStatus(lead.getLeadStatus(), outcome);
        LocalDateTime followUp = request.getNextFollowUpAt() != null
                ? request.getNextFollowUpAt()
                : defaultFollowUp(outcome, newStatus, now);

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
        lead = businessRepository.saveAndFlush(lead); // flush so updatedAt is current in the response

        log.info("Call logged on lead {} by {}: {} -> status {}", lead.getId(), caller.getUsername(), outcome, newStatus);
        return new LogCallResponse(DtoMapper.toDto(lead), DtoMapper.toDto(contact));
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
            return DtoMapper.toDto(lead);
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

        return DtoMapper.toDto(lead);
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
