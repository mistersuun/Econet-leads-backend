package com.econet.leads.service;

import com.econet.leads.config.TimeConfig;
import com.econet.leads.dto.NotificationDTO;
import com.econet.leads.model.Business;
import com.econet.leads.model.LeadStatus;
import com.econet.leads.model.Tender;
import com.econet.leads.model.TenderStatus;
import com.econet.leads.model.User;
import com.econet.leads.realtime.ClaimRegistry;
import com.econet.leads.repository.BusinessRepository;
import com.econet.leads.repository.TenderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * Notifications of the current user: follow-ups due today / overdue on leads assigned to them or
 * unassigned, their claims that expired in the last hour, and tenders closing within 48 h that
 * the team is reviewing or bidding on.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    static final int MAX_ITEMS = 100;
    static final Duration CLAIM_EXPIRED_WINDOW = Duration.ofHours(1);
    static final Duration TENDER_CLOSING_WINDOW = Duration.ofHours(48);
    static final EnumSet<TenderStatus> TENDER_STATUSES = EnumSet.of(TenderStatus.REVIEWING, TenderStatus.BIDDING);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM 'à' HH:mm", Locale.CANADA_FRENCH);

    private final BusinessRepository businessRepository;
    private final TenderRepository tenderRepository;
    private final ClaimRegistry claimRegistry;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<NotificationDTO> forUser(User user) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime todayStart = LocalDate.now(clock).atStartOfDay();
        LocalDateTime tomorrowStart = todayStart.plusDays(1);
        List<NotificationDTO> result = new ArrayList<>();

        for (Business b : businessRepository.findFollowUpsDueForUser(tomorrowStart, LeadStatus.TERMINAL, user.getId(),
                PageRequest.of(0, MAX_ITEMS))) {
            boolean overdue = b.getNextFollowUpAt().isBefore(todayStart);
            result.add(new NotificationDTO(
                    (overdue ? "follow_up_overdue:" : "follow_up_due:") + b.getId(),
                    overdue ? NotificationDTO.FOLLOW_UP_OVERDUE : NotificationDTO.FOLLOW_UP_DUE,
                    b.getBusinessName(),
                    (overdue ? "Rappel en retard depuis le " : "Rappel prévu aujourd'hui à ")
                            + (overdue ? WHEN.format(b.getNextFollowUpAt()) : b.getNextFollowUpAt().toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")))
                            + (b.getAddressCity() != null ? " · " + b.getAddressCity() : ""),
                    b.getId(), null, b.getNextFollowUpAt()));
        }

        for (ClaimRegistry.ExpiredClaim e : claimRegistry.expiredClaimsOf(user.getId(), clock.instant().minus(CLAIM_EXPIRED_WINDOW))) {
            LocalDateTime at = LocalDateTime.ofInstant(e.expiredAt(), TimeConfig.BUSINESS_ZONE);
            result.add(new NotificationDTO(
                    "claim_expired:" + e.leadId() + ":" + e.expiredAt().getEpochSecond(),
                    NotificationDTO.CLAIM_EXPIRED,
                    e.leadName(),
                    "Votre réservation de ce lead a expiré (10 min sans activité)",
                    e.leadId(), null, at));
        }

        for (Tender t : tenderRepository.findClosingBetween(now, now.plus(TENDER_CLOSING_WINDOW), TENDER_STATUSES)) {
            long hours = Duration.between(now, t.getClosingAt()).toHours();
            result.add(new NotificationDTO(
                    "tender_closing:" + t.getId(),
                    NotificationDTO.TENDER_CLOSING,
                    t.getTitle(),
                    "Fermeture dans " + hours + " h (" + WHEN.format(t.getClosingAt()) + ")"
                            + (t.getBuyer() != null ? " · " + t.getBuyer() : ""),
                    null, t.getId(), t.getClosingAt()));
        }

        result.sort(Comparator.comparing(NotificationDTO::at));
        return result.size() > MAX_ITEMS ? result.subList(0, MAX_ITEMS) : result;
    }
}
