package com.econet.leads.dto;

import com.econet.leads.model.Tender;
import com.econet.leads.model.TenderSource;
import com.econet.leads.model.TenderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Tender as returned by /api/tenders (api-contract-2 §3). {@code noticeType}, {@code awardedTo},
 * {@code contractEndAt} and {@code description} are additive extras (null when unknown).
 */
public record TenderDTO(UUID id,
                        TenderSource source,
                        String externalId,
                        String title,
                        String buyer,
                        String region,
                        String category,
                        LocalDateTime publishedAt,
                        LocalDateTime closingAt,
                        String url,
                        BigDecimal estimatedValue,
                        List<String> matchedKeywords,
                        TenderStatus status,
                        String notes,
                        LocalDateTime createdAt,
                        LocalDateTime updatedAt,
                        String description,
                        String noticeType,
                        String awardedTo,
                        LocalDateTime contractEndAt) {

    public static TenderDTO from(Tender t) {
        return new TenderDTO(t.getId(), t.getSource(), t.getExternalId(), t.getTitle(), t.getBuyer(), t.getRegion(),
                t.getCategory(), t.getPublishedAt(), t.getClosingAt(), t.getUrl(), t.getEstimatedValue(),
                t.getMatchedKeywords() != null ? List.copyOf(t.getMatchedKeywords()) : List.of(),
                t.getStatus(), t.getNotes(), t.getCreatedAt(), t.getUpdatedAt(), t.getDescription(),
                t.getNoticeType(), t.getAwardedTo(), t.getContractEndAt());
    }
}
