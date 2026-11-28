package net.onelitefeather.blackhole.backend.guard;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Objects;

/**
 * The configuration of the unattended guard (blackhole.guard.*). Validated when built, so a bad value stops the
 * start-up instead of reaching a login. The unattended level may never be OFF: that would make the unattended mode a
 * no-op. LOCKDOWN is accepted only because the operator sets it.
 *
 * @param unattendedLevel  the level raised while no staff member is on the proxy
 * @param absenceThreshold how long staff must have been absent before the unattended level applies
 * @param nightWindow      the local-time window in which the unattended level applies regardless of absence
 * @param zone             the zone in which the night window is read
 * @param presenceTtl      how old a proxy's heartbeat may be before it stops counting
 */
public record GuardSettings(
        GuardLevel unattendedLevel,
        Duration absenceThreshold,
        NightWindow nightWindow,
        ZoneId zone,
        Duration presenceTtl
) {

    public GuardSettings {
        Objects.requireNonNull(unattendedLevel, "unattended level");
        Objects.requireNonNull(absenceThreshold, "absence threshold");
        Objects.requireNonNull(nightWindow, "night window");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(presenceTtl, "presence ttl");
        if (unattendedLevel == GuardLevel.OFF) {
            throw new IllegalArgumentException("blackhole.guard.unattended.level must be STRICT or LOCKDOWN, not OFF");
        }
        if (absenceThreshold.isNegative() || absenceThreshold.isZero()) {
            throw new IllegalArgumentException("blackhole.guard.unattended.absence-threshold must be positive");
        }
        if (presenceTtl.isNegative() || presenceTtl.isZero()) {
            throw new IllegalArgumentException("blackhole.guard.presence.ttl must be positive");
        }
    }

    /** Builds the settings from the raw configuration values. */
    public static GuardSettings of(String level, Duration absenceThreshold, String nightWindow, String zone, Duration presenceTtl) {
        return new GuardSettings(GuardLevel.valueOf(level.trim().toUpperCase()), absenceThreshold,
                NightWindow.parse(nightWindow), ZoneId.of(zone.trim()), presenceTtl);
    }
}
