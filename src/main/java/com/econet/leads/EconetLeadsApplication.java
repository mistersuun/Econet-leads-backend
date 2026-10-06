package com.econet.leads;

import com.econet.leads.config.TimeConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableCaching
@EnableScheduling
@EnableAsync
public class EconetLeadsApplication {

    public static void main(String[] args) {
        // All timestamps are stored and exposed as America/Montreal wall-clock time (see TimeConfig)
        TimeZone.setDefault(TimeZone.getTimeZone(TimeConfig.BUSINESS_ZONE));
        SpringApplication.run(EconetLeadsApplication.class, args);
    }

}
