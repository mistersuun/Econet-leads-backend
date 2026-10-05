package com.econet.leads.service;

import com.econet.leads.dto.TenderDTO;
import com.econet.leads.dto.TenderSummaryDTO;
import com.econet.leads.dto.TenderUpdateRequest;
import com.econet.leads.exception.ApiException;
import com.econet.leads.model.Tender;
import com.econet.leads.model.TenderSource;
import com.econet.leads.model.TenderStatus;
import com.econet.leads.repository.TenderRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Public tenders: listing/filtering, team follow-up (status, notes), summary counters and the
 * import upsert.
 */
@Service
@RequiredArgsConstructor
public class TenderService {

    /** Statuses that don't count as "open" opportunities in the summary. */
    static final Set<TenderStatus> NOT_PURSUED = EnumSet.of(TenderStatus.WON, TenderStatus.LOST, TenderStatus.IGNORED);

    private final TenderRepository tenderRepository;
    private final Clock clock;

    public record Filters(Collection<TenderStatus> statuses, Collection<TenderSource> sources, String q, Boolean openOnly) {
    }

    /**
     * @param order field + direction; rows with a null value come last whatever the direction, ties by id
     * @param pageable page and size only (its sort is ignored)
     */
    @Transactional(readOnly = true)
    public Page<TenderDTO> find(Filters filters, org.springframework.data.domain.Sort.Order order, Pageable pageable) {
        Specification<Tender> spec = specification(filters, LocalDateTime.now(clock)).and((root, query, cb) -> {
            Class<?> type = query.getResultType();
            if (type != Long.class && type != long.class) {
                var path = root.get(order.getProperty());
                query.orderBy(
                        cb.asc(cb.selectCase().when(cb.isNull(path), 1).otherwise(0)),
                        order.isAscending() ? cb.asc(path) : cb.desc(path),
                        cb.asc(root.get("id")));
            }
            return null;
        });
        Pageable unsorted = org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return tenderRepository.findAll(spec, unsorted).map(TenderDTO::from);
    }

    @Transactional(readOnly = true)
    public TenderDTO get(UUID id) {
        return TenderDTO.from(load(id));
    }

    @Transactional
    public TenderDTO update(UUID id, TenderUpdateRequest request) {
        Tender tender = load(id);
        if (request.getStatus() != null) {
            tender.setStatus(request.getStatus());
        }
        if (request.getNotes() != null) {
            String notes = request.getNotes().trim();
            tender.setNotes(notes.isEmpty() ? null : notes);
        }
        return TenderDTO.from(tenderRepository.saveAndFlush(tender));
    }

    @Transactional(readOnly = true)
    public TenderSummaryDTO summary() {
        LocalDateTime now = LocalDateTime.now(clock);
        return new TenderSummaryDTO(
                tenderRepository.countOpen(now, NOT_PURSUED),
                tenderRepository.countClosingBetween(now, now.plusDays(7), NOT_PURSUED),
                tenderRepository.countByStatus(TenderStatus.BIDDING),
                tenderRepository.countByStatus(TenderStatus.SUBMITTED),
                tenderRepository.countByStatus(TenderStatus.WON));
    }

    /**
     * Insert or refresh one imported tender, in its own transaction (like
     * BusinessService#importRecord) so one bad row never poisons the job.
     * Existing rows keep the team's status and notes; source fields are refreshed with non-null
     * incoming values unless the stored values come from a newer source release.
     *
     * @return true if a new row was created
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean importTender(Tender candidate) {
        if (candidate.getSource() == null || candidate.getExternalId() == null || candidate.getExternalId().isBlank()) {
            throw new IllegalArgumentException("Tender without source/externalId");
        }
        if (candidate.getTitle() == null || candidate.getTitle().isBlank()) {
            throw new IllegalArgumentException("Tender " + candidate.getExternalId() + " has no title");
        }
        if (candidate.getUrl() == null || candidate.getUrl().isBlank()) {
            throw new IllegalArgumentException("Tender " + candidate.getExternalId() + " has no URL");
        }
        var existing = tenderRepository.findBySourceAndExternalId(candidate.getSource(), candidate.getExternalId());
        if (existing.isEmpty()) {
            candidate.setId(null);
            candidate.setStatus(TenderStatus.NEW);
            candidate.setNotes(null);
            tenderRepository.save(candidate);
            return true;
        }
        Tender t = existing.get();
        if (t.getSourceReleaseAt() != null && candidate.getSourceReleaseAt() != null
                && candidate.getSourceReleaseAt().isBefore(t.getSourceReleaseAt())) {
            return false; // an older release of a notice we already have
        }
        t.setTitle(candidate.getTitle());
        t.setUrl(candidate.getUrl());
        if (candidate.getDescription() != null) t.setDescription(candidate.getDescription());
        if (candidate.getBuyer() != null) t.setBuyer(candidate.getBuyer());
        if (candidate.getRegion() != null) t.setRegion(candidate.getRegion());
        if (candidate.getCategory() != null) t.setCategory(candidate.getCategory());
        if (candidate.getPublishedAt() != null) t.setPublishedAt(candidate.getPublishedAt());
        if (candidate.getClosingAt() != null) t.setClosingAt(candidate.getClosingAt());
        if (candidate.getEstimatedValue() != null) t.setEstimatedValue(candidate.getEstimatedValue());
        if (candidate.getMatchedKeywords() != null && !candidate.getMatchedKeywords().isEmpty()) {
            t.setMatchedKeywords(new ArrayList<>(candidate.getMatchedKeywords()));
        }
        // AWARD wins over TENDER: once a notice is awarded it stays an award (and keeps its
        // contract-end closingAt / "Contrat octroyé" category)
        if ("AWARD".equals(t.getNoticeType()) && !"AWARD".equals(candidate.getNoticeType())) {
            if (candidate.getSourceReleaseAt() != null) t.setSourceReleaseAt(candidate.getSourceReleaseAt());
            tenderRepository.save(t);
            return false;
        }
        if (t.getNoticeType() == null || "AWARD".equals(candidate.getNoticeType())) {
            t.setNoticeType(candidate.getNoticeType());
        }
        if (candidate.getAwardedTo() != null) t.setAwardedTo(candidate.getAwardedTo());
        if (candidate.getContractEndAt() != null) t.setContractEndAt(candidate.getContractEndAt());
        if (candidate.getSourceReleaseAt() != null) t.setSourceReleaseAt(candidate.getSourceReleaseAt());
        tenderRepository.save(t);
        return false;
    }

    private Tender load(UUID id) {
        return tenderRepository.findById(id).orElseThrow(() -> ApiException.notFound("Tender not found: " + id));
    }

    static Specification<Tender> specification(Filters f, LocalDateTime now) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (f.statuses() != null && !f.statuses().isEmpty()) {
                predicates.add(root.get("status").in(f.statuses()));
            }
            if (f.sources() != null && !f.sources().isEmpty()) {
                predicates.add(root.get("source").in(f.sources()));
            }
            if (f.q() != null && !f.q().isBlank()) {
                String like = "%" + f.q().trim().toLowerCase(Locale.ROOT)
                        .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), like, '!'),
                        cb.like(cb.lower(root.get("buyer")), like, '!'),
                        cb.like(cb.lower(root.get("externalId")), like, '!'),
                        cb.like(cb.lower(root.get("category")), like, '!')));
            }
            if (Boolean.TRUE.equals(f.openOnly())) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("closingAt"), now));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
