package com.econet.leads.config;

import com.econet.leads.model.Business;
import com.econet.leads.model.CallOutcome;
import com.econet.leads.model.Contact;
import com.econet.leads.model.LeadStatus;
import com.econet.leads.model.User;
import com.econet.leads.repository.BusinessRepository;
import com.econet.leads.repository.ContactRepository;
import com.econet.leads.repository.UserRepository;
import com.econet.leads.service.DataQualityService;
import com.econet.leads.service.LeadService;
import com.econet.leads.util.PhoneFormatter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * LOCAL PROFILE ONLY: seeds ~60 demo leads (data_source = "DEMO") with a mix of pipeline statuses
 * and call history spread over the last 30 days, so the CRM dashboard has data in development.
 *
 * - Runs only with the "local" profile, only when app.seed.demo-data is true (env SEED_DEMO_DATA,
 *   default true in application-local.yml) and only if no DEMO business exists yet.
 * - Creates demo users demo.agent1 / demo.agent2 (USER) and demo.viewer (VIEWER), password demo1234.
 * - Names are fictional and phone numbers use the 555-01xx range reserved for fiction.
 * - Statuses and follow-ups are derived with the real LeadService rules.
 * - Remove with: DELETE FROM businesses WHERE data_source = 'DEMO'; (contacts cascade)
 *
 * This is deliberately not a Flyway migration so demo rows can never reach a real database.
 */
@Component
@Profile("local")
@Order(10)
@Slf4j
public class LocalDemoDataSeeder implements CommandLineRunner {

    public static final String DEMO_SOURCE = "DEMO";
    static final String DEMO_PASSWORD = "demo1234";

    private static final String[] CITIES = {
            "Montréal", "Montréal", "Montréal", "Québec", "Québec", "Laval", "Gatineau", "Longueuil",
            "Sherbrooke", "Trois-Rivières", "Lévis", "Saguenay", "Terrebonne", "Brossard"
    };

    private static final String[][] TYPES_AND_NAMES = {
            {"CPE", "CPE Les Petits Explorateurs", "CPE La Ribambelle", "CPE Les Lucioles", "CPE Pomme d'Api",
                    "CPE Les Petits Pas", "CPE L'Arc-en-ciel", "Garderie Les Bambins Joyeux"},
            {"CHSLD", "CHSLD Saint-Joseph", "CHSLD des Érables", "Centre d'hébergement du Boisé",
                    "CHSLD Notre-Dame-de-la-Merci", "Centre d'hébergement Les Jardins"},
            {"Restaurant", "Bistro Le Comptoir", "Brasserie des Patriotes", "Café Lumière", "Casse-Croûte Chez Réjean",
                    "Restaurant La Belle Époque", "Trattoria Nonna Lucia", "Resto Le Vieux Moulin"},
            {"Clinique", "Clinique Médicale du Plateau", "Clinique Santé Plus", "Centre Médical Saint-Laurent",
                    "Clinique Familiale des Rivières", "Clinique Sans Rendez-vous Centre-Ville"},
            {"Résidence pour aînés", "Résidence Le Félix", "Manoir des Pins", "Résidence Les Belles Années",
                    "Domaine du Parc"},
            {"Hôtel", "Hôtel du Vieux-Port", "Auberge La Seigneurie", "Hôtel Le Champlain", "Gîte de la Rivière"},
            {"Commerce de détail", "Quincaillerie Gagnon", "Boutique Mode Élégance", "Librairie du Quartier",
                    "Fromagerie des Cantons", "Pharmacie Bédard"},
            {"Services professionnels", "Cabinet Comptable Tremblay & Associés", "Notaires Lavoie Pelletier",
                    "Bureau d'Architectes Côté", "Agence Immobilière Bouchard"},
            {"Formation Professionnelle", "Centre de formation professionnelle Fierbourg",
                    "Centre de formation des Métiers", "École hôtelière de la Capitale"},
            {"Économie Sociale", "Coopérative de solidarité Le Grenier", "Friperie Entraide Plus",
                    "Cuisine collective du Quartier"}
    };

    private static final String[] STREETS = {
            "Rue Saint-Denis", "Boulevard René-Lévesque", "Avenue du Mont-Royal", "Rue Sherbrooke",
            "Chemin Sainte-Foy", "Boulevard Saint-Martin", "Rue King Ouest", "Boulevard des Forges",
            "Rue Principale", "Avenue Cartier", "Boulevard Taschereau", "Rue Notre-Dame"
    };

    private static final String[] CONTACT_PEOPLE = {
            "Mme Tremblay", "M. Gagnon", "Mme Roy", "M. Côté", "Mme Bouchard", "M. Gauthier", "Mme Morin",
            "M. Lavoie", "Mme Fortin", "M. Pelletier"
    };

    private static final String[] NOTES = {
            "Laisser un message à la réception.",
            "Responsable de l'entretien absente, rappeler en après-midi.",
            "Contrat actuel avec un concurrent jusqu'en décembre.",
            "Intéressé par un entretien hebdomadaire des bureaux.",
            "Demande une soumission pour le nettoyage des planchers.",
            "Veut comparer avec leur fournisseur actuel.",
            "Très satisfaits de leur fournisseur, pas intéressés.",
            null, null
    };

    private final BusinessRepository businessRepository;
    private final ContactRepository contactRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final DataQualityService dataQualityService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final boolean enabled;

    @PersistenceContext
    private EntityManager em;

    public LocalDemoDataSeeder(BusinessRepository businessRepository,
                               ContactRepository contactRepository,
                               UserRepository userRepository,
                               PasswordEncoder passwordEncoder,
                               DataQualityService dataQualityService,
                               TransactionTemplate transactionTemplate,
                               Clock clock,
                               @Value("${app.seed.demo-data:false}") boolean enabled) {
        this.businessRepository = businessRepository;
        this.contactRepository = contactRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.dataQualityService = dataQualityService;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Override
    public void run(String... args) {
        if (!enabled) {
            log.info("Demo data seeding disabled (app.seed.demo-data=false)");
            return;
        }
        if (businessRepository.countByDataSource(DEMO_SOURCE) > 0) {
            log.info("DEMO businesses already present; skipping demo data seeding");
            return;
        }
        Integer created = transactionTemplate.execute(status -> seed());
        log.info("Seeded {} DEMO businesses with call history (local profile only)", created);
    }

    /** Target pipeline mix for the 60 demo leads. */
    private static final LeadStatus[] TARGETS = buildTargets();

    private static LeadStatus[] buildTargets() {
        List<LeadStatus> list = new ArrayList<>();
        addN(list, LeadStatus.NEW, 18);
        addN(list, LeadStatus.CONTACTED, 13);
        addN(list, LeadStatus.INTERESTED, 8);
        addN(list, LeadStatus.QUOTE_SENT, 6);
        addN(list, LeadStatus.WON, 6);
        addN(list, LeadStatus.LOST, 6);
        addN(list, LeadStatus.DO_NOT_CALL, 3);
        return list.toArray(new LeadStatus[0]);
    }

    private static void addN(List<LeadStatus> list, LeadStatus s, int n) {
        for (int i = 0; i < n; i++) list.add(s);
    }

    private int seed() {
        Random random = new Random(20261005L); // deterministic demo data
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();

        List<User> agents = new ArrayList<>();
        agents.add(ensureUser("demo.agent1", User.UserRole.USER));
        agents.add(ensureUser("demo.agent2", User.UserRole.USER));
        ensureUser("demo.viewer", User.UserRole.VIEWER);
        userRepository.findByUsername("admin").ifPresent(agents::add);

        Set<String> usedNames = new HashSet<>();
        int count = 0;
        for (int i = 0; i < TARGETS.length; i++) {
            LeadStatus target = TARGETS[i];
            String[] typeRow = TYPES_AND_NAMES[i % TYPES_AND_NAMES.length];
            String type = typeRow[0];
            String city = CITIES[random.nextInt(CITIES.length)];
            String name = typeRow[1 + random.nextInt(typeRow.length - 1)];
            if (!usedNames.add(name + "|" + city)) {
                name = name + " - " + city;
                usedNames.add(name + "|" + city);
            }

            Business b = new Business();
            b.setBusinessName(name);
            b.setBusinessType(type);
            b.setAddressStreet((100 + random.nextInt(8900)) + " " + STREETS[random.nextInt(STREETS.length)]);
            b.setAddressCity(city);
            b.setAddressProvince("QC");
            b.setPostalCode(postalCode(city, random));
            // NEW leads: a few without phone so "callable" differs from total
            boolean hasPhone = !(target == LeadStatus.NEW && i % 6 == 5);
            if (hasPhone) {
                String phone = areaCode(city) + "555" + String.format("%04d", 100 + i); // 555-01xx fictional range
                b.setPhone(PhoneFormatter.format(phone));
                b.setPhoneNormalized(PhoneFormatter.normalize(phone));
            }
            if (random.nextInt(3) > 0) {
                b.setEmail("info@" + slug(name) + ".example");
            }
            if (random.nextBoolean()) {
                b.setWebsite("https://www." + slug(name) + ".example");
            }
            b.setDataSource(DEMO_SOURCE);
            b.setExternalId("demo-" + (i + 1));
            b.setLastVerified(now.minusDays(random.nextInt(60)));
            b.setLeadStatus(LeadStatus.NEW);
            b.setContactCount(0);
            b.setDataQualityScore(dataQualityService.calculateQualityScore(b));
            b = businessRepository.save(b);

            // created between 45 and 1 days ago
            LocalDateTime createdAt = now.minusDays(1 + random.nextInt(45)).withHour(8).withMinute(random.nextInt(60)).withSecond(0).withNano(0);
            em.createQuery("UPDATE Business b SET b.createdAt = :c WHERE b.id = :id")
                    .setParameter("c", createdAt).setParameter("id", b.getId()).executeUpdate();

            if (target != LeadStatus.NEW) {
                User agent = agents.get(random.nextInt(agents.size()));
                simulateCalls(b, target, agent, agents, random, now, today);
            }
            count++;
        }
        return count;
    }

    /** Creates the calls leading to the target status, applying the real LeadService rules. */
    private void simulateCalls(Business b, LeadStatus target, User agent, List<User> agents, Random random,
                               LocalDateTime now, LocalDate today) {
        List<CallOutcome> outcomes = new ArrayList<>();
        int noAnswers = random.nextInt(3);
        for (int k = 0; k < noAnswers; k++) {
            outcomes.add(random.nextBoolean() ? CallOutcome.NO_ANSWER : CallOutcome.VOICEMAIL);
        }
        switch (target) {
            case CONTACTED -> outcomes.add(random.nextInt(3) == 0 ? CallOutcome.CALLBACK
                    : (random.nextBoolean() ? CallOutcome.NO_ANSWER : CallOutcome.VOICEMAIL));
            case INTERESTED -> outcomes.add(CallOutcome.INTERESTED);
            case QUOTE_SENT -> {
                outcomes.add(CallOutcome.INTERESTED);
                outcomes.add(CallOutcome.QUOTE_SENT);
            }
            case WON -> {
                outcomes.add(CallOutcome.INTERESTED);
                outcomes.add(CallOutcome.QUOTE_SENT);
                outcomes.add(CallOutcome.WON);
            }
            case LOST -> outcomes.add(CallOutcome.NOT_INTERESTED);
            case DO_NOT_CALL -> outcomes.add(random.nextBoolean() ? CallOutcome.WRONG_NUMBER : CallOutcome.DO_NOT_CALL);
            default -> { }
        }

        // Spread the calls over the last 30 days, on weekdays during business hours, in order
        List<LocalDateTime> times = new ArrayList<>();
        int startOffset = 3 + random.nextInt(27); // first call 3..29 days ago
        LocalDate day = today.minusDays(startOffset);
        for (int k = 0; k < outcomes.size(); k++) {
            day = nextWeekdayNotAfter(day, today);
            LocalDateTime t = day.atTime(LocalTime.of(9 + random.nextInt(8), random.nextInt(60)));
            if (t.isAfter(now)) {
                t = now.minusMinutes(5L * (outcomes.size() - k));
            }
            times.add(t);
            day = day.plusDays(1 + random.nextInt(4));
            if (day.isAfter(today)) {
                day = today;
            }
        }

        LeadStatus status = LeadStatus.NEW;
        LocalDateTime followUp = null;
        for (int k = 0; k < outcomes.size(); k++) {
            CallOutcome outcome = outcomes.get(k);
            LocalDateTime when = times.get(k);
            status = LeadService.nextStatus(status, outcome);
            followUp = LeadService.defaultFollowUp(outcome, status, when);

            Contact c = new Contact();
            c.setBusiness(b);
            c.setUser(random.nextInt(5) == 0 ? agents.get(random.nextInt(agents.size())) : agent);
            c.setContactType(Contact.ContactType.APPEL);
            c.setContactDate(when);
            c.setContactStatus(outcome.name());
            c.setOutcome(outcome.toContactOutcome());
            if (CallOutcome.CONVERSATIONS.contains(outcome)) {
                c.setContactPerson(CONTACT_PEOPLE[random.nextInt(CONTACT_PEOPLE.length)]);
            }
            c.setNotes(NOTES[random.nextInt(NOTES.length)]);
            c.setNextActionDate(followUp);
            c.setNextAction(followUp != null ? "Rappel" : null);
            contactRepository.save(c);
        }

        b.setLeadStatus(status);
        b.setAssignedTo(agent);
        b.setContactCount(outcomes.size());
        b.setLastContactedAt(times.get(times.size() - 1));
        b.setNextFollowUpAt(followUp);
        if (status == LeadStatus.INTERESTED || status == LeadStatus.QUOTE_SENT || status == LeadStatus.WON) {
            b.setEstimatedValue(BigDecimal.valueOf(1500 + 50L * random.nextInt(210)).setScale(2));
        }
        businessRepository.save(b);
    }

    private User ensureUser(String username, User.UserRole role) {
        return userRepository.findByUsername(username).orElseGet(() -> {
            User u = new User();
            u.setUsername(username);
            u.setEmail(username + "@demo.econet-leads.example");
            u.setPasswordHash(passwordEncoder.encode(DEMO_PASSWORD));
            u.setRole(role);
            u.setActive(true);
            return userRepository.save(u);
        });
    }

    private static LocalDate nextWeekdayNotAfter(LocalDate d, LocalDate limit) {
        LocalDate r = d;
        while (r.getDayOfWeek() == DayOfWeek.SATURDAY || r.getDayOfWeek() == DayOfWeek.SUNDAY) {
            r = r.plusDays(1);
        }
        return r.isAfter(limit) ? limit : r;
    }

    private static String areaCode(String city) {
        return switch (city) {
            case "Montréal" -> "514";
            case "Québec", "Lévis", "Saguenay" -> "418";
            case "Gatineau", "Sherbrooke", "Trois-Rivières" -> "819";
            default -> "450";
        };
    }

    private static String postalCode(String city, Random random) {
        String first = switch (city) {
            case "Montréal" -> "H";
            case "Laval" -> "H";
            case "Québec", "Lévis", "Saguenay" -> "G";
            default -> "J";
        };
        String letters = "ABCEGHJKLMNPRSTVWXYZ";
        return first + random.nextInt(10) + letters.charAt(random.nextInt(letters.length())) + " "
                + random.nextInt(10) + letters.charAt(random.nextInt(letters.length())) + random.nextInt(10);
    }

    private static String slug(String name) {
        String s = java.text.Normalizer.normalize(name.toLowerCase(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return s.length() > 40 ? s.substring(0, 40) : s;
    }
}
