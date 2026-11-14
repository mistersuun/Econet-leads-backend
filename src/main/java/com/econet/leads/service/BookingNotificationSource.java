package com.econet.leads.service;

import com.econet.leads.dto.NotificationDTO;
import com.econet.leads.model.BookingLink;
import com.econet.leads.model.Quote;
import com.econet.leads.model.User;
import com.econet.leads.model.Visit;
import com.econet.leads.repository.BookingLinkRepository;
import com.econet.leads.repository.QuoteRepository;
import com.econet.leads.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Notifications of P5 for the current user, appended to {@link NotificationService#forUser}:
 * quotes accepted online in the last 48 h (kind {@code quote_accepted}, to the quote's creator) and
 * visits booked through one of the user's booking links in the last 48 h (kind {@code visit_booked}).
 */
@Component
@RequiredArgsConstructor
public class BookingNotificationSource {

    static final Duration WINDOW = Duration.ofHours(48);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEEE d MMMM 'à' H 'h' mm", Locale.CANADA_FRENCH);

    private final QuoteRepository quoteRepository;
    private final BookingLinkRepository bookingLinkRepository;
    private final VisitRepository visitRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<NotificationDTO> forUser(User user) {
        LocalDateTime since = LocalDateTime.now(clock).minus(WINDOW);
        List<NotificationDTO> result = new ArrayList<>();
        for (Quote q : quoteRepository.findAcceptedSince(user.getId(), since)) {
            result.add(new NotificationDTO("quote_accepted:" + q.getId(), NotificationDTO.QUOTE_ACCEPTED,
                    q.getBusiness().getBusinessName(),
                    "Soumission " + q.getNumber() + " acceptée en ligne par " + q.getAcceptedName() + " · "
                            + EmailService.money(q.getTotal(), Locale.CANADA_FRENCH),
                    q.getBusiness().getId(), null, q.getAcceptedAt()));
        }
        for (BookingLink l : bookingLinkRepository.findUsedSince(user.getId(), since)) {
            if (l.getVisitId() == null) continue;
            Visit visit = visitRepository.findById(l.getVisitId()).orElse(null);
            if (visit == null || !Visit.BOOKED.equals(visit.getStatus())) continue;
            result.add(new NotificationDTO("visit_booked:" + visit.getId(), NotificationDTO.VISIT_BOOKED,
                    l.getBusiness().getBusinessName(),
                    "Visite réservée par le client : " + WHEN.format(visit.getStartsAt()),
                    l.getBusiness().getId(), null, l.getUsedAt()));
        }
        return result;
    }
}
