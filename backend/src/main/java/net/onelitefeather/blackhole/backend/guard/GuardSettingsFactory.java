package net.onelitefeather.blackhole.backend.guard;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import java.time.Duration;

/** Reads the blackhole.guard.* configuration once at start-up. The defaults are in application.yml. */
@Factory
public class GuardSettingsFactory {

    @Singleton
    GuardSettings guardSettings(
            @Value("${blackhole.guard.unattended.level:STRICT}") String level,
            @Value("${blackhole.guard.unattended.absence-threshold:PT20M}") Duration absenceThreshold,
            @Value("${blackhole.guard.unattended.night-window:`01:00-07:00`}") String nightWindow,
            @Value("${blackhole.guard.unattended.zone:Europe/Berlin}") String zone,
            @Value("${blackhole.guard.presence.ttl:PT5M}") Duration presenceTtl
    ) {
        return GuardSettings.of(level, absenceThreshold, nightWindow, zone, presenceTtl);
    }
}
