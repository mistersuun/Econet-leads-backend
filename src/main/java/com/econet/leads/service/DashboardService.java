package com.econet.leads.service;

import com.econet.leads.dto.DashboardDTOs.ActivityDay;
import com.econet.leads.dto.DashboardDTOs.BestHour;
import com.econet.leads.dto.DashboardDTOs.ConversionByType;
import com.econet.leads.dto.DashboardDTOs.Funnel;
import com.econet.leads.dto.DashboardDTOs.MeToday;
import com.econet.leads.dto.DashboardDTOs.Rank;
import com.econet.leads.dto.DashboardDTOs.TeamToday;
import com.econet.leads.dto.DashboardDTOs.TodayCounts;
import com.econet.leads.dto.DashboardDTOs.BreakdownRow;
import com.econet.leads.dto.DashboardDTOs.LeaderboardRow;
import com.econet.leads.dto.DashboardDTOs.OutcomeCount;
import com.econet.leads.dto.DashboardDTOs.PipelineStage;
import com.econet.leads.dto.DashboardDTOs.Summary;
import com.econet.leads.exception.ApiException;
import com.econet.leads.model.CallOutcome;
import com.econet.leads.model.LeadStatus;
import com.econet.leads.model.User;
import com.econet.leads.realtime.PresenceService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
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
    private final PresenceService presenceService;

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


    // ------------------------------------------------------------------ conversion by type (queue priority)

    public static final int CONVERSION_WINDOW_DAYS = 180;
    public static final String CONVERSION_CACHE = "conversionByType";
    /** Laplace smoothing: (positive + 2) / (leadsCalled + 10); a type never called scores 0.2. */
    public static final double SMOOTHING_POSITIVE = 2.0;
    public static final double SMOOTHING_CALLED = 10.0;
    private static final String POSITIVE_SQL = "('WON', 'INTERESTED', 'QUOTE_SENT')";

    /**
     * Observed conversion per business type over the last 180 days, one GROUP BY, cached 10 min
     * (Caffeine, see spring.cache.caffeine.spec). leadsCalled = distinct leads with a call,
     * positive = distinct leads with a WON / INTERESTED / QUOTE_SENT outcome (call or status change).
     */
    @Cacheable(CONVERSION_CACHE)
    public List<ConversionByType> getConversionByType() {
        LocalDateTime since = LocalDate.now(clock).minusDays(CONVERSION_WINDOW_DAYS).atStartOfDay();
        List<ConversionByType> result = new ArrayList<>();
        for (Object row : em.createNativeQuery("""
                SELECT b.business_type,
                       COUNT(DISTINCT CASE WHEN c.contact_type = 'APPEL' THEN c.business_id END),
                       SUM(CASE WHEN c.contact_type = 'APPEL' AND c.outcome IN %1$s THEN 1 ELSE 0 END),
                       COUNT(DISTINCT CASE WHEN c.outcome IN %2$s THEN c.business_id END)
                FROM contacts c
                JOIN businesses b ON b.id = c.business_id
                WHERE c.contact_date >= :since
                GROUP BY b.business_type
                """.formatted(CONVERSATION_SQL, POSITIVE_SQL))
                .setParameter("since", since)
                .getResultList()) {
            Object[] r = (Object[]) row;
            long called = toLong(r[1]);
            long positive = toLong(r[3]);
            result.add(new ConversionByType((String) r[0], called, toLong(r[2]), positive, smoothedRate(called, positive)));
        }
        result.sort(Comparator.comparingDouble(ConversionByType::rate).reversed()
                .thenComparing(ConversionByType::businessType, Comparator.nullsLast(String::compareTo)));
        return result;
    }

    public static double smoothedRate(long leadsCalled, long positive) {
        return (positive + SMOOTHING_POSITIVE) / (leadsCalled + SMOOTHING_CALLED);
    }

    // ------------------------------------------------------------------ funnel

    /** connects = calls whose outcome is not NO_ANSWER / VOICEMAIL / WRONG_NUMBER. */
    public Funnel getFunnel(Range range) {
        Object[] r = (Object[]) em.createNativeQuery("""
                SELECT SUM(CASE WHEN contact_type = 'APPEL' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN contact_type = 'APPEL' AND outcome IS NOT NULL
                                 AND outcome NOT IN ('NO_ANSWER', 'VOICEMAIL', 'WRONG_NUMBER') THEN 1 ELSE 0 END),
                       SUM(CASE WHEN contact_type = 'APPEL' AND outcome IN %1$s THEN 1 ELSE 0 END),
                       COUNT(DISTINCT CASE WHEN outcome = 'INTERESTED' THEN business_id END),
                       COUNT(DISTINCT CASE WHEN outcome = 'QUOTE_SENT' THEN business_id END),
                       COUNT(DISTINCT CASE WHEN outcome = 'WON' THEN business_id END)
                FROM contacts
                WHERE contact_date >= :from AND contact_date < :to
                """.formatted(CONVERSATION_SQL))
                .setParameter("from", range.start())
                .setParameter("to", range.endExclusive())
                .getSingleResult();
        return new Funnel(toLong(r[0]), toLong(r[1]), toLong(r[2]), toLong(r[3]), toLong(r[4]), toLong(r[5]));
    }

    // ------------------------------------------------------------------ best hours

    public static final int BEST_HOURS_FIRST = 8;
    public static final int BEST_HOURS_LAST = 18;

    /**
     * Calls / connects / positive outcomes per (ISO day of week, hour), Mon–Fri 8–18 zero-filled.
     * contact_date is Montreal wall time, so hour and day come straight from it; the day of week
     * is derived in Java (EXTRACT(ISODOW) is not portable to H2).
     */
    public List<BestHour> getBestHours(Range range) {
        long[][] calls = new long[8][24];
        long[][] connects = new long[8][24];
        long[][] positive = new long[8][24];
        for (Object row : em.createNativeQuery("""
                SELECT CAST(contact_date AS DATE),
                       EXTRACT(HOUR FROM contact_date),
                       COUNT(*),
                       SUM(CASE WHEN outcome IS NOT NULL AND outcome NOT IN ('NO_ANSWER', 'VOICEMAIL', 'WRONG_NUMBER') THEN 1 ELSE 0 END),
                       SUM(CASE WHEN outcome IN %1$s THEN 1 ELSE 0 END)
                FROM contacts
                WHERE contact_type = 'APPEL' AND contact_date >= :from AND contact_date < :to
                GROUP BY CAST(contact_date AS DATE), EXTRACT(HOUR FROM contact_date)
                """.formatted(POSITIVE_SQL))
                .setParameter("from", range.start())
                .setParameter("to", range.endExclusive())
                .getResultList()) {
            Object[] r = (Object[]) row;
            int dow = toLocalDate(r[0]).getDayOfWeek().getValue();
            int hour = (int) toLong(r[1]);
            if (hour < 0 || hour > 23) continue;
            calls[dow][hour] += toLong(r[2]);
            connects[dow][hour] += toLong(r[3]);
            positive[dow][hour] += toLong(r[4]);
        }
        List<BestHour> result = new ArrayList<>();
        for (int dow = DayOfWeek.MONDAY.getValue(); dow <= DayOfWeek.FRIDAY.getValue(); dow++) {
            for (int hour = BEST_HOURS_FIRST; hour <= BEST_HOURS_LAST; hour++) {
                result.add(new BestHour(dow, hour, calls[dow][hour], connects[dow][hour], positive[dow][hour]));
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ me / today

    public TodayCounts getTodayCounts(UUID userId) {
        LocalDateTime todayStart = LocalDate.now(clock).atStartOfDay();
        Object[] r = (Object[]) em.createNativeQuery("""
                SELECT SUM(CASE WHEN contact_type = 'APPEL' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN contact_type = 'APPEL' AND outcome IN %1$s THEN 1 ELSE 0 END)
                FROM contacts
                WHERE user_id = :userId AND contact_date >= :today AND contact_date < :tomorrow
                """.formatted(CONVERSATION_SQL))
                .setParameter("userId", userId)
                .setParameter("today", todayStart)
                .setParameter("tomorrow", todayStart.plusDays(1))
                .getSingleResult();
        return new TodayCounts(toLong(r[0]), toLong(r[1]));
    }

    /**
     * Scoreboard of the current user. streakDays = consecutive business days (Mon–Fri) with at
     * least one call, ending today or on the previous business day; rankToday = 1 + number of
     * users with more calls today, out of the active ADMIN/USER accounts.
     */
    public MeToday getMeToday(User user) {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime tomorrowStart = todayStart.plusDays(1);
        UUID userId = user.getId();

        Object[] r = (Object[]) em.createNativeQuery("""
                SELECT SUM(CASE WHEN contact_type = 'APPEL' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN contact_type = 'APPEL' AND outcome IN %1$s THEN 1 ELSE 0 END),
                       COUNT(DISTINCT CASE WHEN outcome = 'QUOTE_SENT' THEN business_id END),
                       COUNT(DISTINCT CASE WHEN outcome = 'WON' THEN business_id END)
                FROM contacts
                WHERE user_id = :userId AND contact_date >= :today AND contact_date < :tomorrow
                """.formatted(CONVERSATION_SQL))
                .setParameter("userId", userId)
                .setParameter("today", todayStart)
                .setParameter("tomorrow", tomorrowStart)
                .getSingleResult();
        long callsToday = toLong(r[0]);

        // rank among callers today
        int ahead = 0;
        for (Object row : em.createNativeQuery("""
                SELECT user_id, COUNT(*)
                FROM contacts
                WHERE contact_type = 'APPEL' AND contact_date >= :today AND contact_date < :tomorrow
                GROUP BY user_id
                """)
                .setParameter("today", todayStart)
                .setParameter("tomorrow", tomorrowStart)
                .getResultList()) {
            Object[] u = (Object[]) row;
            if (!toUuid(u[0]).equals(userId) && toLong(u[1]) > callsToday) {
                ahead++;
            }
        }
        long callers = toLong(em.createNativeQuery(
                "SELECT COUNT(*) FROM users WHERE active = true AND role <> 'VIEWER'").getSingleResult());

        // streak: distinct days with a call over the last 400 days
        Set<LocalDate> daysWithCalls = new HashSet<>();
        for (Object day : em.createNativeQuery("""
                SELECT DISTINCT CAST(contact_date AS DATE)
                FROM contacts
                WHERE user_id = :userId AND contact_type = 'APPEL' AND contact_date >= :since
                """)
                .setParameter("userId", userId)
                .setParameter("since", today.minusDays(400).atStartOfDay())
                .getResultList()) {
            daysWithCalls.add(toLocalDate(day));
        }

        Object best = em.createNativeQuery("""
                SELECT MAX(cnt) FROM (
                    SELECT COUNT(*) AS cnt FROM contacts
                    WHERE user_id = :userId AND contact_type = 'APPEL'
                    GROUP BY CAST(contact_date AS DATE)
                ) t
                """)
                .setParameter("userId", userId)
                .getSingleResult();

        return new MeToday(callsToday, toLong(r[1]), toLong(r[2]), toLong(r[3]),
                user.getDailyCallTarget() != null ? user.getDailyCallTarget() : User.DEFAULT_DAILY_CALL_TARGET,
                user.getDailyConversationTarget() != null ? user.getDailyConversationTarget() : User.DEFAULT_DAILY_CONVERSATION_TARGET,
                streakDays(daysWithCalls, today),
                toLong(best),
                new Rank(ahead + 1, (int) Math.max(callers, 1)));
    }

    /** Consecutive business days with a call ending today, or on the previous business day. */
    public static int streakDays(Set<LocalDate> daysWithCalls, LocalDate today) {
        LocalDate day = previousBusinessDayOrSame(today);
        if (!daysWithCalls.contains(day)) {
            day = previousBusinessDayOrSame(day.minusDays(1));
            if (!daysWithCalls.contains(day)) {
                return 0;
            }
        }
        int streak = 0;
        while (daysWithCalls.contains(day)) {
            streak++;
            day = previousBusinessDayOrSame(day.minusDays(1));
        }
        return streak;
    }

    private static LocalDate previousBusinessDayOrSame(LocalDate date) {
        LocalDate d = date;
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    // ------------------------------------------------------------------ team / today

    public List<TeamToday> getTeamToday() {
        LocalDateTime todayStart = LocalDate.now(clock).atStartOfDay();
        List<TeamToday> result = new ArrayList<>();
        for (Object row : em.createNativeQuery("""
                SELECT u.id, u.username,
                       SUM(CASE WHEN c.contact_type = 'APPEL' THEN 1 ELSE 0 END) AS calls,
                       SUM(CASE WHEN c.contact_type = 'APPEL' AND c.outcome IN %1$s THEN 1 ELSE 0 END) AS conversations
                FROM users u
                LEFT JOIN contacts c ON c.user_id = u.id AND c.contact_date >= :today AND c.contact_date < :tomorrow
                WHERE u.active = true AND u.role <> 'VIEWER'
                GROUP BY u.id, u.username
                ORDER BY calls DESC, u.username ASC
                """.formatted(CONVERSATION_SQL))
                .setParameter("today", todayStart)
                .setParameter("tomorrow", todayStart.plusDays(1))
                .getResultList()) {
            Object[] r = (Object[]) row;
            User stub = new User();
            stub.setId(toUuid(r[0]));
            stub.setUsername((String) r[1]);
            PresenceService.PresenceUser presence = presenceService.of(stub);
            result.add(new TeamToday(stub.getId(), stub.getUsername(), toLong(r[2]), toLong(r[3]),
                    presence.status().name(), presence.leadName()));
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
