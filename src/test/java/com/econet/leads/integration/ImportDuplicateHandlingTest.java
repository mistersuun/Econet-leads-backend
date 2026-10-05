package com.econet.leads.integration;

import com.econet.leads.model.Business;
import com.econet.leads.model.DataSource;
import com.econet.leads.model.ScraperJob;
import com.econet.leads.repository.BusinessRepository;
import com.econet.leads.repository.DataSourceRepository;
import com.econet.leads.repository.ScraperJobRepository;
import com.econet.leads.service.BusinessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces the "Transaction silently rolled back because it has been marked as rollback-only"
 * import failure: several existing rows share the same normalized phone (a chain), so the old
 * Optional-returning findByPhoneNormalized threw IncorrectResultSizeDataAccessException.
 */
@SpringBootTest
@ActiveProfiles("test")
class ImportDuplicateHandlingTest {

    @Autowired BusinessService businessService;
    @Autowired BusinessRepository businessRepository;
    @Autowired DataSourceRepository dataSourceRepository;
    @Autowired ScraperJobRepository scraperJobRepository;
    @Autowired ImportJobTracker jobTracker;
    @Autowired ImportJobRunner jobRunner;

    @BeforeEach
    void clean() {
        scraperJobRepository.deleteAll();
        businessRepository.deleteAll();
    }

    private Business business(String name, String city, String phone, String externalId) {
        Business b = new Business();
        b.setBusinessName(name);
        b.setBusinessType("Restaurant");
        b.setAddressCity(city);
        b.setAddressProvince("QC");
        b.setPhone(phone);
        b.setDataSource("TEST");
        b.setExternalId(externalId);
        return b;
    }

    @Test
    void importRecordMatchesWhenSeveralRowsShareAPhone() {
        // Two existing branches of a chain with the same head-office phone, same city
        businessService.importRecord(business("Poulet Rouge Plateau", "Montréal", "514-555-0100", "a"));
        Business second = business("Poulet Rouge Verdun", "Montréal", "(514) 555-0100", null);
        second.setDataSource("OTHER");
        businessRepository.save(second); // bypass dedup to create the duplicate-phone situation
        second.setPhoneNormalized("5145550100");
        businessRepository.save(second);
        assertThat(businessRepository.findTop20ByPhoneNormalizedOrderByCreatedAtAsc("5145550100")).hasSize(2);

        // A third record with the same phone used to throw IncorrectResultSizeDataAccessException
        BusinessService.UpsertResult result = businessService.importRecord(
                business("Poulet Rouge Rosemont", "Montréal", "514 555 0100", "c"));

        assertThat(result.created()).isFalse();
        assertThat(result.business().getBusinessName()).isEqualTo("Poulet Rouge Plateau");
        assertThat(businessRepository.count()).isEqualTo(2);
    }

    @Test
    void samePhoneInAnotherCityIsNotMergedIntoTheExistingBranch() {
        businessService.importRecord(business("Poulet Rouge Plateau", "Montréal", "514-555-0100", "a"));

        BusinessService.UpsertResult result = businessService.importRecord(
                business("Poulet Rouge Laval", "Laval", "514-555-0100", "b"));

        assertThat(result.created()).isTrue();
        assertThat(businessRepository.count()).isEqualTo(2);
    }

    @Test
    void oneBadRecordDoesNotPoisonTheImport() {
        DataSource source = dataSourceRepository.findBySourceName("Test source").orElseGet(() -> {
            DataSource ds = new DataSource();
            ds.setSourceName("Test source");
            ds.setSourceType(DataSource.SourceType.MANUAL);
            ds.setActive(true);
            return dataSourceRepository.save(ds);
        });
        ScraperJob job = jobTracker.createPendingJob(source);

        Business bad = business(null, "Montréal", "514-555-0199", "bad"); // business_name NOT NULL
        Business existingDupA = business("Café Central", "Québec", "418-555-0001", "x1");
        Business existingDupB = business("Café Central Bis", "Québec", "418-555-0001", "x2");
        Business fresh = business("Boulangerie Fresh", "Gatineau", "819-555-0002", "x3");
        businessService.importRecord(existingDupA);
        Business forcedDuplicate = business("Café Central Annexe", "Québec", "418-555-0001", null);
        forcedDuplicate.setPhoneNormalized("4185550001");
        businessRepository.save(forcedDuplicate);

        List<Business> records = List.of(bad, existingDupB, fresh);
        jobRunner.run(job.getId(), source.getId(), source.getSourceName(), () -> records);

        ScraperJob finished = scraperJobRepository.findById(job.getId()).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(ScraperJob.JobStatus.COMPLETED);
        assertThat(finished.getRecordsProcessed()).isEqualTo(3);
        assertThat(finished.getRecordsAdded()).isEqualTo(1);   // fresh
        assertThat(finished.getRecordsUpdated()).isEqualTo(1); // phone duplicate
        assertThat(finished.getErrors()).contains("Record 1");
        assertThat(finished.getStartedAt()).isNotNull();
        assertThat(finished.getCompletedAt()).isNotNull();
    }

    @Test
    void fetchFailureMarksJobFailed() {
        DataSource ds = new DataSource();
        ds.setSourceName("Broken source " + UUID.randomUUID());
        ds.setSourceType(DataSource.SourceType.MANUAL);
        ds.setActive(true);
        DataSource source = dataSourceRepository.save(ds);
        ScraperJob job = jobTracker.createPendingJob(source);

        jobRunner.run(job.getId(), source.getId(), source.getSourceName(), () -> {
            throw new IllegalStateException("network unreachable");
        });

        ScraperJob finished = scraperJobRepository.findById(job.getId()).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(ScraperJob.JobStatus.FAILED);
        assertThat(finished.getErrors()).contains("network unreachable");
    }
}
