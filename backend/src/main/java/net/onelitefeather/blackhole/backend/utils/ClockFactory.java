package net.onelitefeather.blackhole.backend.utils;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.time.Clock;

/**
 * The application's time source. Services take a {@link Clock} instead of reading the system time,
 * so tests can fix it.
 */
@Factory
public class ClockFactory {

    @Singleton
    @Requires(missingBeans = Clock.class)
    Clock clock() {
        return Clock.systemUTC();
    }
}
