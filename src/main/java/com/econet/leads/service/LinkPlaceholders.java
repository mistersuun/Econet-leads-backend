package com.econet.leads.service;

import com.econet.leads.repository.BookingLinkRepository;
import com.econet.leads.repository.QuoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Values of the {{quoteLink}} and {{bookingLink}} email placeholders: the public URL of the lead's
 * latest quote that can still be accepted, and of its latest unused, unexpired booking link. Empty
 * strings when there is none (EmailService then drops the lines that only carry the placeholder).
 */
@Component
@RequiredArgsConstructor
public class LinkPlaceholders {

    public static final String QUOTE_LINK = "quoteLink";
    public static final String BOOKING_LINK = "bookingLink";

    private final QuoteRepository quoteRepository;
    private final BookingLinkRepository bookingLinkRepository;
    private final PublicLinkService links;
    private final Clock clock;

    public Map<String, String> forLead(UUID leadId) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(QUOTE_LINK, quoteRepository.findOpenForLead(leadId, LocalDate.now(clock))
                .map(q -> links.quoteUrl(q.getToken())).orElse(""));
        values.put(BOOKING_LINK, bookingLinkRepository.findOpenForLead(leadId, LocalDateTime.now(clock))
                .map(l -> links.bookingUrl(l.getToken())).orElse(""));
        return values;
    }
}
