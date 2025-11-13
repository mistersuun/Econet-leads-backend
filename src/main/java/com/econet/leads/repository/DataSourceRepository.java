package com.econet.leads.repository;

import com.econet.leads.model.DataSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DataSourceRepository extends JpaRepository<DataSource, UUID> {

    Optional<DataSource> findBySourceName(String sourceName);

    List<DataSource> findByActive(Boolean active);

    List<DataSource> findBySourceType(DataSource.SourceType sourceType);

    List<DataSource> findByActiveAndSyncFrequency(Boolean active, DataSource.SyncFrequency syncFrequency);
}
