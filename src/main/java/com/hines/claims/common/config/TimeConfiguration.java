package com.hines.claims.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * Makes the system clock an injectable dependency.
 *
 * <p>Without this, every timestamp comes from a static {@code Instant.now()} call
 * buried in whichever class needed it - impossible to control in a test, and
 * impossible to substitute if the service ever needs a different time source.
 */
@Configuration
public class TimeConfiguration {

    /**
     * Microsecond resolution, in UTC.
     *
     * <p><strong>Why truncate.</strong> Java's {@code Instant} holds nanoseconds;
     * PostgreSQL {@code timestamptz} holds microseconds. Persisting an untruncated
     * instant means the database silently rounds it, so the value a POST returns is
     * not the value a later GET returns:
     *
     * <pre>
     *   POST -> 2026-09-08T00:44:37.315326500Z   (nanos, straight from memory)
     *   GET  -> 2026-09-08T00:44:37.315327Z      (micros, read back from Postgres)
     * </pre>
     *
     * <p>Neither response is wrong on its own, so no assertion on either one fails -
     * the defect exists only in the relationship between them. A client that caches
     * the create response and later re-reads sees a mismatch that looks like data
     * corruption.
     *
     * <p>Truncating at the source means the application never holds a precision the
     * database cannot store. The alternative - remembering to truncate at every call
     * site - is the kind of rule that is followed until it isn't.
     *
     * <p><strong>Why UTC.</strong> A service that stores timestamps in the server's
     * local zone produces data whose meaning depends on where it happened to run,
     * and that surfaces as an off-by-hours bug twice a year at daylight-saving
     * boundaries.
     */
    @Bean
    public Clock systemClock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
