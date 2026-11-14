package com.econet.leads.service;

import com.econet.leads.dto.BusinessDTO;
import com.econet.leads.dto.DashboardDTOs.ConversionByType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Queue priority that learns: {@code priority = 0.5 * quality + 0.5 * 100 * smoothed_rate} where
 * the smoothed conversion rate per business type comes from {@link DashboardService#getConversionByType()}
 * (last 180 days, cached 10 min). Types without history use the smoothing prior (0.2 -> 10 points).
 */
@Service
@RequiredArgsConstructor
public class PriorityScoreService {

    public static final double DEFAULT_RATE = DashboardService.smoothedRate(0, 0);

    private final DashboardService dashboardService;

    /** Smoothed rate per business type (types without calls in the window are absent). */
    public Map<String, Double> ratesByType() {
        Map<String, Double> rates = new HashMap<>();
        for (ConversionByType row : dashboardService.getConversionByType()) {
            if (row.businessType() != null) {
                rates.put(row.businessType(), row.rate());
            }
        }
        return rates;
    }

    public double rateOf(String businessType) {
        return ratesByType().getOrDefault(businessType, DEFAULT_RATE);
    }

    public static double score(Integer quality, double rate) {
        double q = quality == null ? 0 : quality;
        return Math.round((0.5 * q + 50.0 * rate) * 10.0) / 10.0;
    }

    public BusinessDTO decorate(BusinessDTO dto) {
        return decorate(dto, ratesByType());
    }

    /**
     * Leads whose Google reviews mention cleanliness problems (places.cleanliness_signals) are warmer:
     * +10 points. The call queue applies the same bonus in SQL, see LeadService#priorityExpression.
     */
    public static final double CLEANLINESS_BONUS = 10.0;

    public static boolean hasCleanlinessSignals(Map<String, Object> places) {
        return places != null && places.get("cleanlinessSignals") instanceof java.util.Collection<?> c && !c.isEmpty();
    }

    public BusinessDTO decorate(BusinessDTO dto, Map<String, Double> rates) {
        double score = score(dto.getDataQualityScore(), rates.getOrDefault(dto.getBusinessType(), DEFAULT_RATE));
        dto.setPriorityScore(hasCleanlinessSignals(dto.getPlaces()) ? score + CLEANLINESS_BONUS : score);
        return dto;
    }
}
