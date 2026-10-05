package com.econet.leads.service;

import com.econet.leads.dto.DashboardDTOs.ActivityDay;
import com.econet.leads.dto.DashboardDTOs.BreakdownRow;
import com.econet.leads.dto.DashboardDTOs.LeaderboardRow;
import com.econet.leads.dto.DashboardDTOs.OutcomeCount;
import com.econet.leads.dto.DashboardDTOs.PipelineStage;
import com.econet.leads.dto.DashboardDTOs.Summary;
import com.econet.leads.exception.ApiException;
import com.econet.leads.model.CallOutcome;
import com.econet.leads.model.LeadStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Dashboard aggregates. Every number is computed in the database with GROUP BY / conditional
 * aggregation; no lead or contact rows are loaded into memory. The SQL is plain ANSI (CASE, COUNT
 * DISTINCT, CAST AS DATE) so it runs on PostgreSQL and on H2 in tests.
 *
 * Definitions:
 * - calls: contacts of type APPEL; conversations: calls whose outcome is one of
 *   {@link CallOutcome#CONVERSATIONS};
 * - quotesSent / won: distinct leads with a contact (call or status change) with outcome
 *   QUOTE_SENT / WON in the range;
 * - date range: [from 00:00, to + 1 day 00:00), America/Montreal wall time.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    public static final int DEFAULT_RANGE_DAYS = 30;
    public static final int MAX_RANGE_DAYS = 731;

    private static final String TERMINAL_SQL = inList(LeadStatus.TERMINAL.stream().map(Enum::name).toList());
    private static final String CONVERSATION_SQL = inList(CallOutcome.CONVERSATIONS.stream().map(Enum::name).toList());

    private static final Map<String, String> BREAKDOWN_COLUMNS = Map.of(
            "type", "business_type",
            "city", "address_city",
            "source", "data_source");

    @PersistenceContext
    private EntityManager em;

    private final Clock clock;

    /** Inclusive date range; defaults to the last 30 days including today. */
    public record Range(LocalDate from, LocalDate to) {
        LocalDateTime start() {
            return from.atStartOfDay();
        }

        LocalDateTime endExclusive() {
            return to.plusDays(1).atStartOfDay();
        }
    }

    public Range resolveRange(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_RANGE_DAYS - 1);
        if (start.isAfter(end)) {
            throw ApiException.badRequest("'from' must be on or before 'to'");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_RANGE_DAYS) {
            throw ApiException.badRequest("Date range must not exceed " + MAX_RANGE_DAYS + " days");
        }
        return new Range(start, end);
    }

    // ------------------------------------------------------------------ summary

    public Summary getSummary(Range range) {
        LocalDateTime todayStart = LocalDate.now(clock).atStartOfDay();
        LocalDateTime tomorrowStart = todayStart.plusDays(1);

        Object[] leads = (Object[]) em.createNativeQuery("""
                SELECT COUNT(*),
                       SUM(CASE WHEN phone IS NOT NULL AND phone <> '' AND lead_status NOT IN %1$s THEN 1 ELSE 0 END),
                       SUM(CASE WHEN created_at >= :from AND created_at < :to THEN 1 ELSE 0 END),
                       SUM(CASE WHEN next_follow_up_at >= :today AND next_follow_up_at < :tomorrow THEN 1 ELSE 0 END),
                       SUM(CASE WHEN next_follow_up_at < :today AND lead_status NOT IN %1$s THEN 1 ELSE 0 END),
                       SUM(CASE WHEN lead_status IN ('INTERESTED', 'QUOTE_SENT') THEN estimated_value ELSE 0 END),
                       SUM(CASE WHEN (phone IS NULL OR phone = '') AND lead_status NOT IN %1$s THEN 1 ELSE 0 END)
                FROM businesses
                """.formatted(TERMINAL_SQL))
                .setParameter("from", range.start())
                .setParameter("to", range.endExclusive())
                .setParameter("today", todayStart)
                .setParameter("tomorrow", tomorrowStart)
                .getSingleResult();

        LocalDateTime scanStart = min(range.start(), todayStart);
        LocalDateTime scanEnd = max(range.endExclusive(), tomorrowStart);
        Object[] contacts = (Object[]) em.createNativeQuery("""
                SELECT SUM(CASE WHEN contact_type = 'APPEL' AND contact_date >= :from AND contact_date < :to THEN 1 ELSE 0 END),
                       SUM(CASE WHEN contact_type = 'APPEL' AND contact_date >= :today AND contact_date < :tomorrow THEN 1 ELSE 0 END),
                       SUM(CASE WHEN contact_type = 'APPEL' AND contact_date >= :from AND contact_date < :to
                                 AND outcome IN %1$s THEN 1 ELSE 0 END),
                       COUNT(DISTINCT CASE WHEN contact_date >= :from AND contact_date < :to AND outcome = 'QUOTE_SENT' THEN business_id END),
                       COUNT(DISTINCT CASE WHEN contact_date >= :from AND contact_date < :to AND outcome = 'WON' THEN business_id END),
                       COUNT(DISTINCT CASE WHEN contact_type = 'APPEL' AND contact_date >= :from AND contact_date < :to THEN business_id END)
                FROM contacts
                WHERE contact_date >= :scanStart AND contact_date < :scanEnd
                """.formatted(CONVERSATION_SQL))
                .setParameter("from", range.start())
                .setParameter("to", range.endExclusive())
                .setParameter("today", todayStart)
                .setParameter("tomorrow", tomorrowStart)
                .setParameter("scanStart", scanStart)
                .setParameter("scanEnd", scanEnd)
                .getSingleResult();

        long won = toLong(contacts[4]);
        long leadsCalled = toLong(contacts[5]);
        double conversionRate = leadsCalled == 0 ? 0.0 : Math.min(1.0, (double) won / leadsCalled);

        return new Summary(
                toLong(leads[0]),
                toLong(leads[1]),
                toLong(leads[2]),
                toLong(contacts[0]),
                toLong(contacts[1]),
                toLong(contacts[2]),
                toLong(contacts[3]),
                won,
                conversionRate,
                toLong(leads[3]),
                toLong(leads[4]),
                toBigDecimal(leads[5]),
                toLong(leads[6]));
    }

    // ------------------------------------------------------------------ pipeline

    public List<PipelineStage> getPipeline() {
        Map<LeadStatus, Long> counts = new EnumMap<>(LeadStatus.class);
        for (Object row : em.createNativeQuery(
                "SELECT lead_status, COUNT(*) FROM businesses GROUP BY lead_status").getResultList()) {
            Object[] r = (Object[]) row;
            try {
                counts.put(LeadStatus.valueOf((String) r[0]), toLong(r[1]));
            } catch (IllegalArgumentException ignored) {
                // unknown status values cannot exist (CHECK constraint) but never fail the dashboard
            }
        }
        List<PipelineStage> result = new ArrayList<>();
        for (LeadStatus status : LeadStatus.values()) {
            result.add(new PipelineStage(status, counts.getOrDefault(status, 0L)));
        }
        return result;
    }

    // ------------------------------------------------------------------ activity

    public List<ActivityDay> getActivity(Range range) {
        Map<LocalDate, ActivityDay> byDay = new HashMap<>();
        for (Object row : em.createNativeQuery("""
                SELECT CAST(contact_date AS DATE),
                       SUM(CASE WHEN contact_type = 'APPEL' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN contact_type = 'APPEL' AND outcome IN %s THEN 1 ELSE 0 END),
                       COUNT(DISTINCT CASE WHEN outcome = 'WON' THEN business_id END)
                FROM contacts
                WHERE contact_date >= :from AND contact_date < :to
                GROUP BY CAST(contact_date AS DATE)
                """.formatted(CONVERSATION_SQL))
                .setParameter("from", range.start())
                .setParameter("to", range.endExclusive())
                .getResultList()) {
            Object[] r = (Object[]) row;
            LocalDate day = toLocalDate(r[0]);
            byDay.put(day, new ActivityDay(day, toLong(r[1]), toLong(r[2]), toLong(r[3])));
        }

        List<ActivityDay> result = new ArrayList<>();
        for (LocalDate d = range.from(); !d.isAfter(range.to()); d = d.plusDays(1)) {
            result.add(byDay.getOrDefault(d, new ActivityDay(d, 0, 0, 0)));
        }
        return result;
    }

    // ------------------------------------------------------------------ breakdown

    public List<BreakdownRow> getBreakdown(String dimension, int limit) {
        String column = dimension == null ? null : BREAKDOWN_COLUMNS.get(dimension.toLowerCase());
        if (column == null) {
            throw ApiException.badRequest("dimension must be one of: type, city, source");
        }
        int max = Math.max(1, Math.min(limit, 100));
        // column comes from the whitelist above, never from user input
        String label = "COALESCE(NULLIF(" + column + ", ''), 'Inconnu')";
        Query query = em.createNativeQuery("""
                SELECT %1$s AS label,
                       COUNT(*) AS total,
                       SUM(CASE WHEN contact_count > 0 OR lead_status <> 'NEW' THEN 1 ELSE 0 END) AS contacted,
                       SUM(CASE WHEN lead_status = 'WON' THEN 1 ELSE 0 END) AS won
                FROM businesses
                GROUP BY %1$s
                ORDER BY total DESC, label ASC
                """.formatted(label));
        query.setMaxResults(max);

        List<BreakdownRow> result = new ArrayList<>();
        for (Object row : query.getResultList()) {
            Object[] r = (Object[]) row;
            result.add(new BreakdownRow((String) r[0], toLong(r[1]), toLong(r[2]), toLong(r[3])));
        }
        return result;
    }

    // ------------------------------------------------------------------ outcomes

    /** Call outcomes in the range, all CallOutcome values (zero-filled), by count desc. Legacy outcomes are not reported. */
    public List<OutcomeCount> getOutcomes(Range range) {
        Map<CallOutcome, Long> counts = new EnumMap<>(CallOutcome.class);
        for (Object row : em.createNativeQuery("""
                SELECT outcome, COUNT(*)
                FROM contacts
                WHERE contact_type = 'APPEL' AND outcome IS NOT NULL
                  AND contact_date >= :from AND contact_date < :to
                GROUP BY outcome
                """)
                .setParameter("from", range.start())
                .setParameter("to", range.endExclusive())
                .getResultList()) {
            Object[] r = (Object[]) row;
            try {
                counts.put(CallOutcome.valueOf((String) r[0]), toLong(r[1]));
            } catch (IllegalArgumentException ignored) {
                // legacy CONTRAT / REFUS / EN_ATTENTE
            }
        }
        return java.util.Arrays.stream(CallOutcome.values())
                .map(o -> new OutcomeCount(o, counts.getOrDefault(o, 0L)))
                .sorted(Comparator.comparingLong(OutcomeCount::count).reversed())
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------ leaderboard

    public List<LeaderboardRow> getLeaderboard(Range range) {
        List<LeaderboardRow> result = new ArrayList<>();
        for (Object row : em.createNativeQuery("""
                SELECT u.id, u.username,
                       SUM(CASE WHEN c.contact_type = 'APPEL' THEN 1 ELSE 0 END) AS calls,
                       SUM(CASE WHEN c.contact_type = 'APPEL' AND c.outcome IN %s THEN 1 ELSE 0 END) AS conversations,
                       COUNT(DISTINCT CASE WHEN c.outcome = 'WON' THEN c.business_id END) AS won
                FROM contacts c
                JOIN users u ON u.id = c.user_id
                WHERE c.contact_date >= :from AND c.contact_date < :to
                GROUP BY u.id, u.username
                ORDER BY calls DESC, u.username ASC
                """.formatted(CONVERSATION_SQL))
                .setParameter("from", range.start())
                .setParameter("to", range.endExclusive())
                .getResultList()) {
            Object[] r = (Object[]) row;
            result.add(new LeaderboardRow(toUuid(r[0]), (String) r[1], toLong(r[2]), toLong(r[3]), toLong(r[4])));
        }
        return result;
    }

    // ------------------------------------------------------------------ helpers

    private static String inList(List<String> values) {
        return values.stream().map(v -> "'" + v + "'").collect(Collectors.joining(", ", "(", ")"));
    }

    private static long toLong(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal bd) return bd;
        return new BigDecimal(o.toString());
    }

    private static LocalDate toLocalDate(Object o) {
        if (o instanceof LocalDate ld) return ld;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof LocalDateTime ldt) return ldt.toLocalDate();
        if (o instanceof java.util.Date d) return new java.sql.Date(d.getTime()).toLocalDate();
        return LocalDate.parse(o.toString().substring(0, 10));
    }

    private static UUID toUuid(Object o) {
        if (o instanceof UUID u) return u;
        if (o instanceof byte[] b && b.length == 16) {
            ByteBuffer bb = ByteBuffer.wrap(b);
            return new UUID(bb.getLong(), bb.getLong());
        }
        return UUID.fromString(o.toString());
    }

    private static LocalDateTime min(LocalDateTime a, LocalDateTime b) {
        return a.isBefore(b) ? a : b;
    }

    private static LocalDateTime max(LocalDateTime a, LocalDateTime b) {
        return a.isAfter(b) ? a : b;
    }
}
