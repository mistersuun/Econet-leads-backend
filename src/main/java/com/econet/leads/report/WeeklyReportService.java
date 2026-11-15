package com.econet.leads.report;

import com.econet.leads.config.MailSettings;
import com.econet.leads.dto.DashboardDTOs.BestHour;
import com.econet.leads.exception.ApiException;
import com.econet.leads.model.CallOutcome;
import com.econet.leads.model.User;
import com.econet.leads.repository.ContactRepository;
import com.econet.leads.repository.UserRepository;
import com.econet.leads.service.DashboardService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Monday coaching email: last week's numbers of one caller compared with the week before, the
 * team's best calling hours, the caller's most frequent objections and a link to the CRM.
 * Rendering never needs SMTP; only {@link #sendAll()} does.
 *
 * Definitions (same as the dashboard): calls = APPEL contacts; conversations = calls whose outcome is
 * in {@link CallOutcome#CONVERSATIONS}; connects = calls answered by a person (outcome other than
 * NO_ANSWER / VOICEMAIL / WRONG_NUMBER); quotes / wins = distinct leads with a QUOTE_SENT / WON outcome.
 * Weeks run Monday to Sunday, America/Montreal wall time.
 */
@Service
@Slf4j
public class WeeklyReportService {

    static final int BEST_HOURS_LOOKBACK_DAYS = 28;
    static final int BEST_HOURS_MIN_CALLS = 3;
    static final int TOP_OBJECTIONS = 3;
    private static final Locale FR = Locale.CANADA_FRENCH;
    private static final String CONVERSATION_SQL = "(" + CallOutcome.CONVERSATIONS.stream()
            .map(o -> "'" + o.name() + "'").collect(Collectors.joining(", ")) + ")";

    @PersistenceContext
    private EntityManager em;

    private final UserRepository userRepository;
    private final ContactRepository contactRepository;
    private final DashboardService dashboardService;
    private final MailSettings mailSettings;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final Clock clock;
    private final String crmUrl;
    private final String companyName;

    public WeeklyReportService(UserRepository userRepository,
                               ContactRepository contactRepository,
                               DashboardService dashboardService,
                               MailSettings mailSettings,
                               ObjectProvider<JavaMailSender> mailSender,
                               Clock clock,
                               @Value("${app.crm-url:http://localhost:5173}") String crmUrl,
                               @Value("${app.company.name:EcoNet Propreté}") String companyName) {
        this.userRepository = userRepository;
        this.contactRepository = contactRepository;
        this.dashboardService = dashboardService;
        this.mailSettings = mailSettings;
        this.mailSender = mailSender;
        this.clock = clock;
        this.crmUrl = crmUrl == null || crmUrl.isBlank() ? "http://localhost:5173" : crmUrl.replaceAll("/+$", "");
        this.companyName = companyName;
    }

    public record Week(long calls, long conversations, long connects, long quotes, long won) {
        /** Share of calls answered by a person, 0-100 (one decimal). */
        public double connectRate() {
            return calls == 0 ? 0 : BigDecimal.valueOf(connects * 100.0 / calls).setScale(1, RoundingMode.HALF_UP).doubleValue();
        }
    }

    public record Slot(int dow, int hour, long calls, double connectRate) {
    }

    public record Objection(String label, long count) {
    }

    public record Report(String username, LocalDate weekStart, LocalDate weekEnd, Week current, Week previous,
                         int streakDays, List<Slot> bestHours, List<Objection> topObjections, String crmUrl) {
    }

    public record Rendered(String subject, String text, String html) {
    }

    // ------------------------------------------------------------------ data

    /** Last full Monday-Sunday week before the week of "today". */
    public LocalDate lastWeekStart() {
        return LocalDate.now(clock).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
    }

    @Transactional(readOnly = true)
    public Report build(User user) {
        LocalDate start = lastWeekStart();
        LocalDate end = start.plusDays(6);
        Week current = week(user, start);
        Week previous = week(user, start.minusWeeks(1));

        LocalDate lookbackFrom = end.minusDays(BEST_HOURS_LOOKBACK_DAYS - 1);
        List<Slot> best = bestHours(dashboardService.getBestHours(dashboardService.resolveRange(lookbackFrom, end)));

        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, String> display = new LinkedHashMap<>();
        for (List<String> list : contactRepository.findObjectionsByUserBetween(user.getId(), start.atStartOfDay(), end.plusDays(1).atStartOfDay())) {
            if (list == null) continue;
            for (String o : list) {
                if (o == null || o.isBlank()) continue;
                String key = o.trim().toLowerCase(FR);
                display.putIfAbsent(key, o.trim());
                counts.merge(key, 1L, Long::sum);
            }
        }
        List<Objection> top = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP_OBJECTIONS)
                .map(e -> new Objection(display.get(e.getKey()), e.getValue()))
                .toList();

        int streak = dashboardService.getMeToday(user).streakDays();
        return new Report(user.getUsername(), start, end, current, previous, streak, best, top, crmUrl);
    }

    private Week week(User user, LocalDate monday) {
        Object[] r = (Object[]) em.createNativeQuery("""
                SELECT SUM(CASE WHEN contact_type = 'APPEL' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN contact_type = 'APPEL' AND outcome IN %1$s THEN 1 ELSE 0 END),
                       SUM(CASE WHEN contact_type = 'APPEL' AND outcome IS NOT NULL
                                 AND outcome NOT IN ('NO_ANSWER', 'VOICEMAIL', 'WRONG_NUMBER') THEN 1 ELSE 0 END),
                       COUNT(DISTINCT CASE WHEN outcome = 'QUOTE_SENT' THEN business_id END),
                       COUNT(DISTINCT CASE WHEN outcome = 'WON' THEN business_id END)
                FROM contacts
                WHERE user_id = :userId AND contact_date >= :from AND contact_date < :to
                """.formatted(CONVERSATION_SQL))
                .setParameter("userId", user.getId())
                .setParameter("from", monday.atStartOfDay())
                .setParameter("to", monday.plusDays(7).atStartOfDay())
                .getSingleResult();
        return new Week(num(r[0]), num(r[1]), num(r[2]), num(r[3]), num(r[4]));
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    /** The two (day, hour) slots with the best connect rate; needs a few calls to count. */
    static List<Slot> bestHours(List<BestHour> data) {
        return data.stream()
                .filter(h -> h.calls() >= BEST_HOURS_MIN_CALLS)
                .map(h -> new Slot(h.dow(), h.hour(), h.calls(), h.connects() * 100.0 / h.calls()))
                .sorted(Comparator.comparingDouble(Slot::connectRate).reversed()
                        .thenComparing(Comparator.comparingLong(Slot::calls).reversed())
                        .thenComparingInt(Slot::dow).thenComparingInt(Slot::hour))
                .limit(2)
                .toList();
    }

    // ------------------------------------------------------------------ rendering

    public Rendered render(Report r) {
        String subject = "Votre semaine chez " + companyName + " : " + r.current().calls() + " appels, "
                + r.current().conversations() + " conversations";
        String range = dayMonth(r.weekStart()) + " au " + dayMonth(r.weekEnd());

        List<String> lines = new ArrayList<>();
        lines.add("Bonjour " + r.username() + ",");
        lines.add("");
        lines.add("Voici votre semaine du " + range + ".");
        lines.add("");
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Appels", String.valueOf(r.current().calls()), delta(r.current().calls(), r.previous().calls())});
        rows.add(new String[]{"Conversations", String.valueOf(r.current().conversations()), delta(r.current().conversations(), r.previous().conversations())});
        rows.add(new String[]{"Taux de jointure", percent(r.current().connectRate()), deltaPoints(r.current().connectRate(), r.previous().connectRate(), r.previous().calls())});
        rows.add(new String[]{"Soumissions envoyées", String.valueOf(r.current().quotes()), delta(r.current().quotes(), r.previous().quotes())});
        rows.add(new String[]{"Contrats gagnés", String.valueOf(r.current().won()), delta(r.current().won(), r.previous().won())});
        for (String[] row : rows) {
            lines.add("- " + row[0] + " : " + row[1] + " (" + row[2] + ")");
        }
        lines.add("");
        String streak = r.streakDays() == 0 ? "Aucune série en cours : un appel lundi la relance."
                : "Série en cours : " + r.streakDays() + (r.streakDays() > 1 ? " jours ouvrables" : " jour ouvrable") + " d'appels.";
        lines.add(streak);
        String best = null;
        if (!r.bestHours().isEmpty()) {
            best = r.bestHours().stream().map(WeeklyReportService::slotLabel).collect(Collectors.joining(" et "));
            lines.add("Meilleures plages de l'équipe (4 dernières semaines) : " + best + ".");
        }
        if (!r.topObjections().isEmpty()) {
            lines.add("");
            lines.add("Objections les plus entendues :");
            for (Objection o : r.topObjections()) {
                lines.add("- " + o.label() + " (" + o.count() + " fois)");
            }
        }
        lines.add("");
        lines.add("Ouvrir le CRM : " + r.crmUrl() + "/call");
        lines.add("");
        lines.add("Pour ne plus recevoir ce courriel : Réglages, Mon compte, Préférences, « Courriel hebdomadaire ».");
        String text = String.join("\n", lines);

        StringBuilder h = new StringBuilder();
        h.append("<div style=\"font-family:-apple-system,Segoe UI,Helvetica,Arial,sans-serif;color:#1f2933;max-width:520px;line-height:1.5\">");
        h.append("<p>Bonjour ").append(esc(r.username())).append(",</p>");
        h.append("<p>Voici votre semaine du ").append(esc(range)).append(".</p>");
        h.append("<table style=\"border-collapse:collapse;width:100%\">");
        for (String[] row : rows) {
            h.append("<tr><td style=\"padding:6px 0;border-bottom:1px solid #e4e7eb\">").append(esc(row[0]))
                    .append("</td><td style=\"padding:6px 8px;border-bottom:1px solid #e4e7eb;text-align:right\"><strong>")
                    .append(esc(row[1])).append("</strong></td><td style=\"padding:6px 0;border-bottom:1px solid #e4e7eb;text-align:right;color:#52606d\">")
                    .append(esc(row[2])).append("</td></tr>");
        }
        h.append("</table>");
        h.append("<p>").append(esc(streak)).append("</p>");
        if (best != null) {
            h.append("<p>Meilleures plages de l'équipe (4 dernières semaines) : <strong>").append(esc(best)).append("</strong>.</p>");
        }
        if (!r.topObjections().isEmpty()) {
            h.append("<p>Objections les plus entendues :</p><ul>");
            for (Objection o : r.topObjections()) {
                h.append("<li>").append(esc(o.label())).append(" (").append(o.count()).append(" fois)</li>");
            }
            h.append("</ul>");
        }
        h.append("<p><a href=\"").append(esc(r.crmUrl() + "/call")).append("\" style=\"display:inline-block;background:#3f6b5a;color:#ffffff;")
                .append("padding:10px 16px;border-radius:8px;text-decoration:none\">Ouvrir le CRM</a></p>");
        h.append("<p style=\"color:#7b8794;font-size:13px\">Pour ne plus recevoir ce courriel : Réglages, Mon compte, Préférences, « Courriel hebdomadaire ».</p>");
        h.append("</div>");
        return new Rendered(subject, text, h.toString());
    }

    private static String esc(String s) {
        return HtmlUtils.htmlEscape(s == null ? "" : s, "UTF-8");
    }

    private static String dayMonth(LocalDate d) {
        return d.getDayOfMonth() + " " + d.getMonth().getDisplayName(TextStyle.FULL, FR);
    }

    static String slotLabel(Slot s) {
        String day = DayOfWeek.of(s.dow()).getDisplayName(TextStyle.FULL, FR);
        return day + " " + s.hour() + " h";
    }

    static String percent(double v) {
        return String.format(FR, "%.1f", v).replace(",0", "") + " %";
    }

    static String delta(long now, long before) {
        if (before == 0) return now == 0 ? "stable" : "semaine précédente : 0";
        long d = now - before;
        if (d == 0) return "stable";
        return (d > 0 ? "+" : "") + d + " vs semaine précédente";
    }

    static String deltaPoints(double now, double before, long previousCalls) {
        if (previousCalls == 0) return "semaine précédente : aucun appel";
        double d = Math.round((now - before) * 10) / 10.0;
        if (d == 0) return "stable";
        return (d > 0 ? "+" : "") + String.format(FR, "%.1f", d).replace(",0", "") + " points vs semaine précédente";
    }

    // ------------------------------------------------------------------ sending

    public boolean mailConfigured() {
        return mailSettings.isConfigured() && mailSender.getIfAvailable() != null;
    }

    /** Sends the report to every active ADMIN/USER with an email who has not opted out. Returns the number sent. */
    public int sendAll() {
        JavaMailSender sender = mailSettings.isConfigured() ? mailSender.getIfAvailable() : null;
        if (sender == null) {
            throw ApiException.serviceUnavailable("Courriel non configuré (définir SMTP_HOST, SMTP_PORT, SMTP_USERNAME, SMTP_PASSWORD)");
        }
        List<User> recipients = userRepository.findByActiveTrueAndWeeklyReportTrueAndRoleIn(
                EnumSet.of(User.UserRole.ADMIN, User.UserRole.USER));
        int sent = 0;
        for (User user : recipients) {
            if (user.getEmail() == null || user.getEmail().isBlank()) continue;
            try {
                Rendered rendered = render(build(user));
                MimeMessage message = sender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
                helper.setFrom(mailSettings.effectiveFrom(), mailSettings.getFromName());
                helper.setTo(user.getEmail());
                helper.setSubject(rendered.subject());
                helper.setText(rendered.text(), rendered.html());
                sender.send(message);
                sent++;
            } catch (MessagingException | UnsupportedEncodingException | RuntimeException e) {
                // never log the address or the body
                log.warn("Weekly report for user {} failed: {}", user.getUsername(), e.getClass().getSimpleName());
            }
        }
        log.info("Weekly coaching emails sent: {} of {} eligible users", sent, recipients.size());
        return sent;
    }
}
