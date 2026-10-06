package com.econet.leads.controller;

import com.econet.leads.dto.TenderDTO;
import com.econet.leads.dto.TenderSummaryDTO;
import com.econet.leads.dto.TenderUpdateRequest;
import com.econet.leads.exception.ApiException;
import com.econet.leads.model.TenderSource;
import com.econet.leads.model.TenderStatus;
import com.econet.leads.service.TenderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

/**
 * Public tenders (appels d'offres). Reads for every role; PATCH needs USER/ADMIN (SecurityConfig:
 * non-GET /api/** requires ADMIN or USER).
 */
@RestController
@RequestMapping("/api/tenders")
@RequiredArgsConstructor
@Tag(name = "Tenders", description = "Public tenders (CanadaBuys, SEAO) matching cleaning keywords")
public class TenderController {

    static final Set<String> SORTABLE_FIELDS = Set.of("closingAt", "publishedAt", "createdAt");
    static final int MAX_PAGE_SIZE = 200;

    private final TenderService tenderService;

    @GetMapping
    @Operation(summary = "List tenders", description = "Filters: status, source (repeatable or comma list), q (title/buyer/number/category), openOnly (closingAt >= now). sortBy closingAt|publishedAt|createdAt (default closingAt ASC, nulls last)")
    public ResponseEntity<Page<TenderDTO>> list(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "50") int size,
                                                @RequestParam(required = false) List<String> status,
                                                @RequestParam(required = false) List<String> source,
                                                @RequestParam(required = false) String q,
                                                @RequestParam(required = false) Boolean openOnly,
                                                @RequestParam(defaultValue = "closingAt") String sortBy,
                                                @RequestParam(required = false) String sortDirection) {
        TenderService.Filters filters = new TenderService.Filters(
                parseEnums(status, "status", TenderStatus::valueOf, TenderStatus.values()),
                parseEnums(source, "source", TenderSource::valueOf, TenderSource.values()),
                q, openOnly);
        Sort sort = buildSort(sortBy, sortDirection);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        return ResponseEntity.ok(tenderService.find(filters, sort.iterator().next(), pageable));
    }

    @GetMapping("/summary")
    @Operation(summary = "Tender counters", description = "{ open, closingThisWeek, bidding, submitted, won }")
    public ResponseEntity<TenderSummaryDTO> summary() {
        return ResponseEntity.ok(tenderService.summary());
    }

    @GetMapping("/{id}")
    public ResponseEntity<TenderDTO> get(@PathVariable UUID id) {
        return ResponseEntity.ok(tenderService.get(id));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update follow-up", description = "{ status?, notes? } — USER/ADMIN")
    public ResponseEntity<TenderDTO> update(@PathVariable UUID id, @Valid @RequestBody TenderUpdateRequest request) {
        return ResponseEntity.ok(tenderService.update(id, request));
    }

    static Sort buildSort(String sortBy, String sortDirection) {
        if (!SORTABLE_FIELDS.contains(sortBy)) {
            throw ApiException.badRequest("sortBy must be one of " + new TreeSet<>(SORTABLE_FIELDS));
        }
        Sort.Direction direction;
        if (sortDirection == null || sortDirection.isBlank()) {
            // Deadlines read naturally soonest first; dates of publication/creation newest first
            direction = "closingAt".equals(sortBy) ? Sort.Direction.ASC : Sort.Direction.DESC;
        } else if ("ASC".equalsIgnoreCase(sortDirection)) {
            direction = Sort.Direction.ASC;
        } else if ("DESC".equalsIgnoreCase(sortDirection)) {
            direction = Sort.Direction.DESC;
        } else {
            throw ApiException.badRequest("sortDirection must be ASC or DESC");
        }
        // nulls last + id tie-breaker are applied by TenderService (criteria API has no NULLS LAST)
        return Sort.by(direction, sortBy);
    }

    private static <E extends Enum<E>> List<E> parseEnums(List<String> raw, String name, Function<String, E> parser, E[] all) {
        List<E> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        for (String part : raw) {
            for (String token : part.split(",")) {
                String t = token.trim();
                if (t.isEmpty()) continue;
                try {
                    result.add(parser.apply(t.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    throw ApiException.badRequest("Invalid " + name + " '" + t + "', expected one of " + Arrays.toString(all));
                }
            }
        }
        return result;
    }
}
