package com.econet.leads.service;

import com.econet.leads.config.TimeConfig;
import com.econet.leads.dto.DiscoverDTOs;
import com.econet.leads.dto.SavedSearchDTOs;
import com.econet.leads.exception.ApiException;
import com.econet.leads.integration.ImportJobTracker;
import com.econet.leads.integration.ImportService;
import com.econet.leads.integration.osm.OsmCategories;
import com.econet.leads.integration.osm.OsmDiscoveryService;
import com.econet.leads.model.DataSource;
import com.econet.leads.model.SavedSearch;
import com.econet.leads.model.ScraperJob;
import com.econet.leads.model.User;
import com.econet.leads.repository.DataSourceRepository;
import com.econet.leads.repository.SavedSearchRepository;
import com.econet.leads.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Saved discovery searches. The daily scheduler (06:00 America/Montreal) runs those that are due
 * (monthly = 30 days since the last run, weekly = 7, counted in calendar days) through the OSM
 * discovery service and imports only candidates that are not already leads, assigned to the
 * search's assignee when there is one.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SavedSearchService {

    public static final String SOURCE_NAME = "Recherches enregistrées";
    static final int NAME_MAX = 150;

    private final SavedSearchRepository repository;
    private final UserRepository userRepository;
    private final OsmDiscoveryService discoveryService;
    private final DataSourceRepository dataSourceRepository;
    private final ImportService importService;
    private final ImportJobTracker jobTracker;
    private final Clock clock;

    // ------------------------------------------------------------------ CRUD

    @Transactional(readOnly = true)
    public List<SavedSearchDTOs.Response> list() {
        return repository.findAllByOrderByCreatedAtDesc().stream().map(this::toResponse).toList();
    }

    @Transactional
    public SavedSearchDTOs.Response create(SavedSearchDTOs.Request request, User creator) {
        SavedSearch s = new SavedSearch();
        s.setCreatedBy(creator);
        apply(s, request, true);
        return toResponse(repository.save(s));
    }

    @Transactional
    public SavedSearchDTOs.Response update(UUID id, SavedSearchDTOs.Request request) {
        SavedSearch s = find(id);
        apply(s, request, false);
        return toResponse(repository.save(s));
    }

    @Transactional
    public void delete(UUID id) {
        repository.delete(find(id));
    }

    private SavedSearch find(UUID id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Recherche enregistrée introuvable : " + id));
    }

    private void apply(SavedSearch s, SavedSearchDTOs.Request r, boolean creating) {
        if (r == null) {
            throw ApiException.badRequest("Corps de requête requis");
        }
        String name = r.name() == null ? "" : r.name().strip();
        if (name.isEmpty() || name.length() > NAME_MAX) {
            throw ApiException.badRequest("name est requis (" + NAME_MAX + " caractères maximum)");
        }
        String city = r.city() == null ? "" : r.city().strip();
        if (city.isEmpty() || city.length() > 100 || city.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.badRequest("city est requis (100 caractères maximum)");
        }
        if (r.categories() == null || r.categories().isEmpty()) {
            throw ApiException.badRequest("categories est requis : " + String.join(", ", OsmCategories.ALL.keySet()));
        }
        Set<String> categories = new LinkedHashSet<>();
        for (String c : r.categories()) {
            String key = c == null ? "" : c.strip().toLowerCase(Locale.ROOT);
            if (!OsmCategories.ALL.containsKey(key)) {
                throw ApiException.badRequest("Catégorie inconnue '" + c + "' (attendu : " + String.join(", ", OsmCategories.ALL.keySet()) + ")");
            }
            categories.add(key);
        }
        int max = r.maxResults() == null ? (creating ? OsmDiscoveryService.DEFAULT_LIMIT : s.getMaxResults()) : r.maxResults();
        if (max < 1 || max > OsmDiscoveryService.MAX_LIMIT) {
            throw ApiException.badRequest("maxResults doit être entre 1 et " + OsmDiscoveryService.MAX_LIMIT);
        }
        String frequency = r.frequency() == null ? (creating ? SavedSearch.MONTHLY : s.getFrequency()) : r.frequency().strip().toLowerCase(Locale.ROOT);
        if (!SavedSearch.MONTHLY.equals(frequency) && !SavedSearch.WEEKLY.equals(frequency)) {
            throw ApiException.badRequest("frequency doit être monthly ou weekly");
        }
        User assignee = null;
        if (r.assignToUserId() != null) {
            assignee = userRepository.findById(r.assignToUserId())
                    .filter(u -> Boolean.TRUE.equals(u.getActive()))
                    .orElseThrow(() -> ApiException.badRequest("Utilisateur introuvable : " + r.assignToUserId()));
        }
        s.setName(name);
        s.setCity(city);
        s.setCategories(new ArrayList<>(categories));
        s.setMaxResults(max);
        s.setFrequency(frequency);
        s.setAssignTo(assignee);
        if (r.active() != null) {
            s.setActive(r.active());
        }
    }

    private SavedSearchDTOs.Response toResponse(SavedSearch s) {
        return SavedSearchDTOs.Response.of(s).withNextRunAt(nextRun(s));
    }

    /** First day (06:00) the search is due again; null when paused. */
    LocalDateTime nextRun(SavedSearch s) {
        if (!s.isActive()) {
            return null;
        }
        LocalDate day = s.getLastRunAt() == null ? LocalDate.now(clock) : s.getLastRunAt().toLocalDate().plusDays(intervalDays(s));
        if (day.isBefore(LocalDate.now(clock))) {
            day = LocalDate.now(clock);
        }
        return day.atTime(6, 0);
    }

    // ------------------------------------------------------------------ running

    /** Starts a background job running this search now (409 when one is already running). */
    public ScraperJob runNow(UUID id) {
        SavedSearch s = find(id);
        DataSource source = source();
        return importService.startTask(source, jobId -> {
            try {
                jobTracker.markRunning(jobId);
                int[] counts = execute(id);
                jobTracker.complete(jobId, counts[0], counts[1], 0, null,
                        counts[1] + " nouveau(x) lead(s) sur " + counts[0] + " résultat(s) pour « " + s.getName() + " »");
            } catch (RuntimeException e) {
                log.warn("Saved search {} failed: {}", id, e.toString());
                jobTracker.fail(jobId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        });
    }

    /** Runs the searches that are due; one failing search never stops the others. Returns how many ran. */
    public int runDue() {
        LocalDate today = LocalDate.now(clock);
        int ran = 0;
        for (SavedSearch s : repository.findByActiveTrue()) {
            if (!isDue(s, today)) {
                continue;
            }
            try {
                int[] counts = execute(s.getId());
                ran++;
                log.info("Saved search '{}' ran: {} candidates, {} new leads", s.getName(), counts[0], counts[1]);
            } catch (RuntimeException e) {
                log.warn("Saved search '{}' failed: {}", s.getName(), e.toString());
            }
        }
        return ran;
    }

    boolean isDue(SavedSearch s, LocalDate today) {
        return s.isActive() && (s.getLastRunAt() == null
                || !s.getLastRunAt().toLocalDate().plusDays(intervalDays(s)).isAfter(today));
    }

    static int intervalDays(SavedSearch s) {
        return SavedSearch.WEEKLY.equals(s.getFrequency()) ? 7 : 30;
    }

    /** @return {candidates found, leads added} */
    int[] execute(UUID id) {
        SavedSearch s = find(id);
        var response = discoveryService.search(s.getCity(), s.getCategories(), s.getMaxResults());
        List<DiscoverDTOs.Candidate> fresh = response.candidates().stream().filter(c -> c.existingLeadId() == null).toList();
        int added = 0;
        if (!fresh.isEmpty()) {
            User assignee = s.getAssignTo();
            added = discoveryService.importCandidates(fresh, assignee != null, assignee).added();
        }
        SavedSearch saved = find(id);
        saved.setLastRunAt(LocalDateTime.now(clock));
        saved.setLastAdded(added);
        repository.save(saved);
        return new int[]{response.candidates().size(), added};
    }

    private DataSource source() {
        return dataSourceRepository.findBySourceName(SOURCE_NAME).orElseGet(() -> {
            DataSource ds = new DataSource();
            ds.setSourceName(SOURCE_NAME);
            ds.setSourceType(DataSource.SourceType.OPENSTREETMAP);
            ds.setSyncFrequency(DataSource.SyncFrequency.MANUAL);
            ds.setActive(false);
            ds.setConfig(Map.of("importer", "SAVED_SEARCH"));
            return dataSourceRepository.save(ds);
        });
    }
}
