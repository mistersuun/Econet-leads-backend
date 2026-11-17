package com.econet.leads.integration.website;

import com.econet.leads.integration.ImportReport;
import com.econet.leads.integration.RecordStream;
import com.econet.leads.model.Business;
import com.econet.leads.model.DataSource;
import com.econet.leads.model.LeadStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Reads the website's booking requests and contact submissions (read-only, SELECT only) as leads.
 *
 * Incremental: rows with {@code created_at > lastSync - 1 day} (every row on the first run), so a
 * request that arrives while a run is in progress is picked up by the next one; the overlap is
 * harmless because leads are keyed by externalId ({@code booking:<id>} / {@code contact:<id>}) and
 * BusinessService.importRecord updates instead of duplicating. The website database is never
 * written to: requests are not marked as imported.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebsiteRequestsImporter {

    public static final String IMPORTER = "WEBSITE_REQUESTS";
    public static final String SOURCE_NAME = "Site web – demandes";
    public static final String DATA_SOURCE = "Site web";
    public static final String TYPE_RESIDENTIAL = "Demande web – résidentiel";
    public static final String TYPE_COMMERCIAL = "Demande web – commercial";
    public static final String TYPE_CONTACT = "Demande web – contact";
    public static final String TYPE_ESTIMATE = "Demande web – estimation";
    static final int MAX_ROWS_PER_TABLE = 5000;
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    /** What the post-import hook needs to know about a lead (note text, kind for the SSE event). */
    public record Inbound(String kind, String note, java.time.Instant requestedAt) {
        public Inbound(String kind, String note) {
            this(kind, note, null);
        }
    }

    private final WebsiteDb websiteDb;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** The estimate_requests table is optional (created by the website's supabase/estimate-requests.sql): log its absence once. */
    private final java.util.concurrent.atomic.AtomicBoolean estimateTableMissingLogged = new java.util.concurrent.atomic.AtomicBoolean();

    /** Inbound info of the leads of the current run, keyed by externalId. */
    private final Map<String, Inbound> inboundByExternalId = new java.util.concurrent.ConcurrentHashMap<>();

    public Inbound inboundOf(String externalId) {
        return inboundByExternalId.remove(externalId);
    }

    public RecordStream<Business> stream(DataSource dataSource, ImportReport report) {
        if (!websiteDb.isConfigured()) {
            throw new IllegalArgumentException("La source « " + SOURCE_NAME + " » n'est pas configurée : définissez "
                    + "WEBSITE_DB_URL, WEBSITE_DB_USERNAME et WEBSITE_DB_PASSWORD (Postgres du site, lecture seule).");
        }
        LocalDateTime lastSync = dataSource.getLastSync();
        Timestamp since = lastSync == null ? null : Timestamp.valueOf(lastSync.minusDays(1));
        return sink -> {
            report.note(since == null ? "Première synchronisation : toutes les demandes" : "Demandes créées après " + since.toLocalDateTime());
            int[] counts = new int[3];
            JdbcTemplate jdbc = websiteDb.jdbc();
            boolean more = readBookings(jdbc, since, b -> {
                counts[0]++;
                return sink.test(b);
            });
            if (more) {
                more = readContacts(jdbc, since, b -> {
                    counts[1]++;
                    return sink.test(b);
                });
            }
            if (more) {
                readEstimates(jdbc, since, report, b -> {
                    counts[2]++;
                    return sink.test(b);
                });
            }
            report.note(counts[0] + " réservations, " + counts[1] + " messages de contact et " + counts[2] + " demandes d'estimation lus");
        };
    }

    // ------------------------------------------------------------------ booking_requests

    private boolean readBookings(JdbcTemplate jdbc, Timestamp since, Predicate<Business> sink) {
        String sql = "SELECT id, first_name, last_name, phone, email, address, city, postal_code, property_type, "
                + "property_size, service_name, preferred_date, preferred_time, frequency, additional_services, total, "
                + "special_instructions, language, created_at FROM booking_requests"
                + (since != null ? " WHERE created_at > ?" : "") + " ORDER BY created_at ASC LIMIT " + MAX_ROWS_PER_TABLE;
        return query(jdbc, sql, since, rs -> sink.test(toBooking(rs)));
    }

    Business toBooking(ResultSet rs) throws SQLException {
        String id = str(rs, "id");
        String first = str(rs, "first_name");
        String last = str(rs, "last_name");
        String name = join(" ", first, last);
        String email = str(rs, "email");
        String propertyType = str(rs, "property_type");
        boolean commercial = propertyType != null
                && (propertyType.equalsIgnoreCase("office") || propertyType.equalsIgnoreCase("retail"));
        BigDecimal total = decimal(rs, "total");
        String frequency = str(rs, "frequency");
        String language = str(rs, "language");
        String instructions = str(rs, "special_instructions");

        Business b = base("booking:" + id);
        b.setBusinessName(truncate(name != null ? name : (email != null ? email : "Demande web " + id), 255));
        b.setBusinessType(commercial ? TYPE_COMMERCIAL : TYPE_RESIDENTIAL);
        b.setPhone(truncate(str(rs, "phone"), 20));
        b.setEmail(validEmail(email));
        b.setAddressStreet(truncate(str(rs, "address"), 255));
        b.setAddressCity(truncate(str(rs, "city"), 100));
        String postal = str(rs, "postal_code");
        b.setPostalCode(postal != null && postal.replaceAll("\\s", "").length() <= 7 ? postal : null);
        b.setEstimatedValue(total != null && total.compareTo(new BigDecimal("99999999")) < 0 ? total : null);

        Map<String, String> details = new LinkedHashMap<>();
        put(details, "Service", str(rs, "service_name"));
        put(details, "Date souhaitée", join(" ", str(rs, "preferred_date"), str(rs, "preferred_time")));
        put(details, "Fréquence", frequencyLabel(frequency));
        put(details, "Superficie", str(rs, "property_size"));
        put(details, "Options", join(", ", list(rs, "additional_services").toArray(new String[0])));
        put(details, "Estimation", total != null ? total.stripTrailingZeros().toPlainString() + " $" : null);
        put(details, "Instructions", instructions);
        put(details, "Langue", language);
        b.setSourceDetails(details);

        Map<String, Object> qualification = new LinkedHashMap<>();
        if (validEmail(email) != null) qualification.put("email", validEmail(email));
        String lang = language == null ? null : language.toLowerCase(Locale.ROOT);
        if ("fr".equals(lang) || "en".equals(lang)) qualification.put("language", lang);
        String wanted = frequencyWanted(frequency);
        if (wanted != null) qualification.put("frequencyWanted", wanted);
        if (instructions != null) qualification.put("notes", truncate(instructions, 5000));
        b.setQualification(qualification.isEmpty() ? null : qualification);

        inboundByExternalId.put(b.getExternalId(), new Inbound("booking", "Demande de réservation reçue sur le site", requestedAt(rs)));
        return b;
    }

    // ------------------------------------------------------------------ contact_submissions

    private boolean readContacts(JdbcTemplate jdbc, Timestamp since, Predicate<Business> sink) {
        String sql = "SELECT id, first_name, last_name, phone, email, service_type, property_size, preferred_date, message, urgent_request, "
                + "created_at FROM contact_submissions" + (since != null ? " WHERE created_at > ?" : "")
                + " ORDER BY created_at ASC LIMIT " + MAX_ROWS_PER_TABLE;
        return query(jdbc, sql, since, rs -> sink.test(toContact(rs)));
    }

    Business toContact(ResultSet rs) throws SQLException {
        String id = str(rs, "id");
        String name = join(" ", str(rs, "first_name"), str(rs, "last_name")); // real schema: first_name + last_name
        String email = str(rs, "email");
        String message = str(rs, "message");

        Business b = base("contact:" + id);
        b.setBusinessName(truncate(name != null ? name : (email != null ? email : "Demande web " + id), 255));
        b.setBusinessType(TYPE_CONTACT);
        b.setPhone(truncate(str(rs, "phone"), 20));
        b.setEmail(validEmail(email));

        Map<String, String> details = new LinkedHashMap<>();
        if (truthy(rs.getObject("urgent_request"))) {
            details.put("Urgent", "oui"); // follow-up is "now" either way: the call goes first
        }
        put(details, "Service", str(rs, "service_type"));
        put(details, "Superficie", str(rs, "property_size"));
        put(details, "Date préférée", str(rs, "preferred_date"));
        put(details, "Message", message);
        b.setSourceDetails(details);

        inboundByExternalId.put(b.getExternalId(), new Inbound("contact", message != null ? message : "Message reçu sur le site", requestedAt(rs)));
        return b;
    }

    // ------------------------------------------------------------------ estimate_requests

    /**
     * Visitors who asked to receive their estimate by email (consent is enforced by the table). The table is
     * created by the website's supabase/estimate-requests.sql; when it does not exist yet the source is skipped.
     */
    private boolean readEstimates(JdbcTemplate jdbc, Timestamp since, ImportReport report, Predicate<Business> sink) {
        String sql = "SELECT id, email, first_name, phone, service, area, property_type, add_ons, frequency, total, language, "
                + "created_at FROM estimate_requests WHERE consent = TRUE" + (since != null ? " AND created_at > ?" : "")
                + " ORDER BY created_at ASC LIMIT " + MAX_ROWS_PER_TABLE;
        try {
            return query(jdbc, sql, since, rs -> sink.test(toEstimate(rs)));
        } catch (BadSqlGrammarException e) {
            String state = e.getCause() instanceof SQLException cause0 ? cause0.getSQLState() : null;
            // Postgres 42P01 / H2 42S02, 42102: the table has not been created on the website side yet
            if ("42P01".equals(state) || "42S02".equals(state) || "42102".equals(state)) {
                if (estimateTableMissingLogged.compareAndSet(false, true)) {
                    log.info("Website table estimate_requests not found: estimate requests are skipped until supabase/estimate-requests.sql is run");
                }
                return true;
            }
            // anything else (permission denied, missing column...) is a real problem: say so on every run
            String cause = "42501".equals(state) ? "permission refusée (accordez SELECT sur estimate_requests)"
                    : "42703".equals(state) ? "colonne manquante (relancez supabase/estimate-requests.sql)"
                    : "erreur SQL " + state;
            log.warn("Website table estimate_requests could not be read ({}), estimate requests skipped", state);
            report.note("Demandes d'estimation ignorées : " + cause);
            return true;
        }
    }

    Business toEstimate(ResultSet rs) throws SQLException {
        String id = str(rs, "id");
        String first = str(rs, "first_name");
        String email = str(rs, "email");
        BigDecimal total = decimal(rs, "total");
        String frequency = str(rs, "frequency");
        String language = str(rs, "language");

        Business b = base("estimate:" + id);
        b.setBusinessName(truncate(first != null ? first : (email != null ? email : "Estimation web " + id), 255));
        b.setBusinessType(TYPE_ESTIMATE);
        b.setPhone(truncate(str(rs, "phone"), 20));
        b.setEmail(validEmail(email));
        b.setEstimatedValue(total != null && total.signum() > 0 && total.compareTo(new BigDecimal("99999999")) < 0 ? total : null);

        List<String> addOns = new ArrayList<>();
        for (String a : list(rs, "add_ons")) addOns.add(addOnLabel(a));

        Map<String, String> details = new LinkedHashMap<>();
        put(details, "Service", serviceLabel(str(rs, "service")));
        put(details, "Superficie", areaLabel(str(rs, "area")));
        put(details, "Type", propertyTypeLabel(str(rs, "property_type")));
        put(details, "Options", join(", ", addOns.toArray(new String[0])));
        put(details, "Fréquence", frequencyLabel(frequency));
        put(details, "Estimation", total != null && total.signum() > 0 ? total.stripTrailingZeros().toPlainString() + " $" : "Devis personnalisé");
        put(details, "Langue", language);
        b.setSourceDetails(details);

        Map<String, Object> qualification = new LinkedHashMap<>();
        if (validEmail(email) != null) qualification.put("email", validEmail(email));
        String lang = language == null ? null : language.toLowerCase(Locale.ROOT);
        if ("fr".equals(lang) || "en".equals(lang)) qualification.put("language", lang);
        String wanted = frequencyWanted(frequency);
        if (wanted != null) qualification.put("frequencyWanted", wanted);
        b.setQualification(qualification.isEmpty() ? null : qualification);

        inboundByExternalId.put(b.getExternalId(), new Inbound("estimate", "Estimation demandée sur le site", requestedAt(rs)));
        return b;
    }

    /** French labels of the estimator's ids (src/app/shared/pricing.ts on the website); unknown values are kept as-is. */
    static String serviceLabel(String id) {
        if (id == null) return null;
        return switch (id) {
            case "residential" -> "Résidentiel";
            case "commercial" -> "Commercial";
            case "deep_cleaning" -> "Grand ménage";
            case "post_construction" -> "Après-construction";
            default -> id;
        };
    }

    static String areaLabel(String id) {
        if (id == null) return null;
        return switch (id) {
            case "under_800" -> "Moins de 800 pi²";
            case "800_1500" -> "800 à 1500 pi²";
            case "1500_2500" -> "1500 à 2500 pi²";
            case "over_2500" -> "Plus de 2500 pi²";
            default -> id;
        };
    }

    static String propertyTypeLabel(String id) {
        if (id == null) return null;
        return switch (id) {
            case "apartment" -> "Appartement / condo";
            case "house" -> "Maison unifamiliale";
            case "townhouse" -> "Maison en rangée";
            case "office" -> "Bureau";
            case "retail" -> "Commerce de détail";
            default -> id;
        };
    }

    static String addOnLabel(String id) {
        if (id == null) return null;
        return switch (id) {
            case "windows" -> "Vitres intérieures";
            case "oven" -> "Four";
            case "fridge" -> "Réfrigérateur";
            case "basement" -> "Sous-sol";
            case "garage" -> "Garage";
            default -> id;
        };
    }

    // ------------------------------------------------------------------ helpers

    private Business base(String externalId) {
        Business b = new Business();
        b.setDataSource(DATA_SOURCE);
        b.setExternalId(externalId);
        b.setLeadStatus(LeadStatus.INTERESTED);
        b.setNextFollowUpAt(LocalDateTime.now(clock)); // speed to lead: call today, first in the queue
        return b;
    }

    @FunctionalInterface
    private interface RowSink {
        boolean accept(ResultSet rs) throws SQLException;
    }

    /** @return false when the sink asked to stop */
    /** When the visitor sent the request (the row's created_at), or null when unreadable. */
    private static java.time.Instant requestedAt(ResultSet rs) {
        try {
            Timestamp ts = rs.getTimestamp("created_at");
            return ts == null ? null : ts.toInstant();
        } catch (SQLException e) {
            return null;
        }
    }

    private boolean query(JdbcTemplate jdbc, String sql, Timestamp since, RowSink sink) {
        ResultSetExtractor<Boolean> extractor = rs -> {
            while (rs.next()) {
                if (!sink.accept(rs)) {
                    return false;
                }
            }
            return true;
        };
        Boolean done = since != null ? jdbc.query(sql, extractor, since) : jdbc.query(sql, extractor);
        return !Boolean.FALSE.equals(done);
    }

    /** French label for the CRM; unknown values are kept as-is. */
    static String frequencyLabel(String frequency) {
        String wanted = frequencyWanted(frequency);
        if (wanted == null) return frequency;
        return switch (wanted) {
            case "weekly" -> "Hebdomadaire";
            case "bi-weekly" -> "Aux 2 semaines";
            case "monthly" -> "Mensuel";
            case "one-time" -> "Une fois";
            default -> frequency;
        };
    }

    static String frequencyWanted(String frequency) {
        if (frequency == null) return null;
        String f = frequency.toLowerCase(Locale.ROOT).replaceAll("[\\s_]+", "-");
        return switch (f) {
            case "weekly", "week", "hebdomadaire" -> "weekly";
            case "biweekly", "bi-weekly", "every-two-weeks", "every-2-weeks", "aux-deux-semaines", "bihebdomadaire" -> "bi-weekly";
            case "monthly", "month", "mensuel" -> "monthly";
            case "one-time", "onetime", "once", "one-off", "ponctuel" -> "one-time";
            default -> null;
        };
    }

    private static String validEmail(String email) {
        return email != null && EMAIL.matcher(email).matches() && email.length() <= 255 ? email : null;
    }

    private static String str(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) return null;
        String s = value.toString().strip();
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal decimal(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) return null;
        if (value instanceof BigDecimal d) return d;
        try {
            return new BigDecimal(value.toString().strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** additional_services as a Postgres array, a JSON array text, or a plain comma separated text. */
    private List<String> list(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        List<String> result = new ArrayList<>();
        if (value == null) return result;
        if (value instanceof Array array) {
            Object[] items = (Object[]) array.getArray();
            for (Object item : items) if (item != null) result.add(item.toString().strip());
        } else {
            String text = value.toString().strip();
            if (text.startsWith("[")) {
                try {
                    mapper.readValue(text, new TypeReference<List<Object>>() { })
                            .forEach(o -> { if (o != null) result.add(o.toString().strip()); });
                } catch (Exception e) {
                    result.add(text);
                }
            } else if (!text.isEmpty()) {
                for (String part : text.split(",")) result.add(part.strip());
            }
        }
        result.removeIf(String::isEmpty);
        return result;
    }

    private static boolean truthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        String s = value.toString().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("t") || s.equals("yes") || s.equals("oui") || s.equals("1");
    }

    private static String join(String separator, String... parts) {
        List<String> present = new ArrayList<>();
        for (String p : parts) if (p != null && !p.isBlank()) present.add(p.strip());
        return present.isEmpty() ? null : String.join(separator, present);
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) map.put(key, truncate(value, 1000));
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
