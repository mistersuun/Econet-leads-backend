package com.econet.leads.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Bounded executor for background imports. Imports are long-running and hit external APIs, so we
 * keep the pool small; when both threads are busy up to {@code queue-capacity} jobs wait, beyond
 * that new imports are rejected (the job is marked FAILED and the API returns 503).
 */
@Configuration
public class AsyncConfig {

    public static final String IMPORT_EXECUTOR = "importExecutor";

    @Bean(name = IMPORT_EXECUTOR)
    public ThreadPoolTaskExecutor importExecutor(
            @Value("${app.import.pool-size:2}") int poolSize,
            @Value("${app.import.queue-capacity:20}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("import-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
