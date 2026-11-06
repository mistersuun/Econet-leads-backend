package com.econet.leads.dto;

import com.econet.leads.model.DataSource;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * A data source as returned by GET /api/data-sources: the entity fields plus {@code configured}
 * (false only for the website source while WEBSITE_DB_URL is unset; true for every other source).
 */
public record DataSourceDTO(UUID id, String sourceName, DataSource.SourceType sourceType, String sourceUrl,
                            LocalDateTime lastSync, DataSource.SyncFrequency syncFrequency, Boolean active,
                            Map<String, Object> config, Integer recordsCount, LocalDateTime createdAt,
                            LocalDateTime updatedAt, boolean configured) {

    public static DataSourceDTO of(DataSource d, boolean configured) {
        return new DataSourceDTO(d.getId(), d.getSourceName(), d.getSourceType(), d.getSourceUrl(), d.getLastSync(),
                d.getSyncFrequency(), d.getActive(), d.getConfig(), d.getRecordsCount(), d.getCreatedAt(),
                d.getUpdatedAt(), configured);
    }
}
