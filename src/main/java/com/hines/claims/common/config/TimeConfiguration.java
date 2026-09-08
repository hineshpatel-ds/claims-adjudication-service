package com.hines.claims.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Makes the system clock an injectable dependency.
 *
 * <p>Without this, every timestamp comes from a static {@code Instant.now()} call
 * buried in whichever class needed it - impossible to control in a test, and
 * impossible to substitute if the service ever needs a different time source.
 *
 * <p>UTC explicitly, not {@code Clock.systemDefaultZone()}. A service that stores
 * timestamps in the server's local zone produces data whose meaning depends on
 * where it happened to run, and that difference surfaces as an off-by-hours bug
 * twice a year when daylight saving shifts.
 */
@Configuration
public class TimeConfiguration {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
