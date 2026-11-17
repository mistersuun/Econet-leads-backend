package com.econet.leads.service;

import com.econet.leads.model.PlacesUsage;
import com.econet.leads.repository.PlacesUsageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

/** Daily Google Places call budget (PLACES_DAILY_LIMIT, default 100), counted per business day. */
@Service
@RequiredArgsConstructor
public class PlacesUsageService {

    private final PlacesUsageRepository repository;
    private final Clock clock;

    @Value("${app.places.daily-limit:100}")
    private int dailyLimit;

    public int dailyLimit() {
        return dailyLimit;
    }

    @Transactional(readOnly = true)
    public int usedToday() {
        return repository.findById(LocalDate.now(clock)).map(PlacesUsage::getCalls).orElse(0);
    }

    public boolean limitReached() {
        return usedToday() >= dailyLimit;
    }

    /** Reserves one call; false when today's budget is spent. Serialized per instance (each repository call is its own transaction). */
    public synchronized boolean tryConsume() {
        LocalDate today = LocalDate.now(clock);
        PlacesUsage usage = repository.findById(today).orElseGet(() -> new PlacesUsage(today, 0));
        if (usage.getCalls() >= dailyLimit) {
            return false;
        }
        usage.setCalls(usage.getCalls() + 1);
        repository.save(usage);
        return true;
    }
}
