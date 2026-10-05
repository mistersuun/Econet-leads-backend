package com.econet.leads.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Business time zone. All LocalDateTime values (API, database TIMESTAMP columns, follow-up rules)
 * are wall-clock times in America/Montreal. The JVM default zone is also set to this zone in
 * {@link com.econet.leads.EconetLeadsApplication#main} so LocalDateTime.now() and Hibernate agree.
 */
@Configuration
public class TimeConfig {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Montreal");

    @Bean
    public Clock clock() {
        return Clock.system(BUSINESS_ZONE);
    }
}
