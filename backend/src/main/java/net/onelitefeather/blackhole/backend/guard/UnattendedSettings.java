package net.onelitefeather.blackhole.backend.guard;

import io.micronaut.core.annotation.Nullable;

import java.time.Duration;
import java.time.ZoneId;

/**
 * The stored unattended settings of the join guard: the row of join_guard_settings. The values are checked by
 * {@link GuardSettingsRules} before they are saved.
 *
 * @param unattendedLevel    the level raised while no staff member is on a proxy (STRICT or LOCKDOWN)
 * @param absenceThreshold   how long staff must have been absent before the unattended level applies
 * @param nightWindowEnabled whether the night window applies; its times are kept while it is disabled
 * @param nightWindow        the local-time window, end exclusive, which may cross midnight
 * @param zone               the zone the night window is read in
 * @param updatedAt          when the row was last written, epoch milliseconds
 * @param updatedByOid       the administrator who saved the row, or null while it is the seeded configuration
 */
public record UnattendedSettings(
        GuardLevel unattendedLevel,
        Duration absenceThreshold,
        boolean nightWindowEnabled,
        NightWindow nightWindow,
        ZoneId zone,
        long updatedAt,
        @Nullable String updatedByOid
) {

    /** The same values, stamped as saved by the given administrator at the given time. */
    public UnattendedSettings savedAt(long at, String oid) {
        return new UnattendedSettings(this.unattendedLevel, this.absenceThreshold, this.nightWindowEnabled, this.nightWindow,
                this.zone, at, oid);
    }

    /** The values of a fresh row: the configured values from blackhole.guard.unattended.*, with the night window enabled. */
    public static UnattendedSettings seeded(GuardSettings configuration, long at) {
        return new UnattendedSettings(configuration.unattendedLevel(), configuration.absenceThreshold(), true,
                configuration.nightWindow(), configuration.zone(), at, null);
    }

    /** Whether two rows hold the same values, ignoring who saved them and when. */
    public boolean sameValuesAs(UnattendedSettings other) {
        return this.unattendedLevel == other.unattendedLevel
                && this.absenceThreshold.equals(other.absenceThreshold)
                && this.nightWindowEnabled == other.nightWindowEnabled
                && this.nightWindow.equals(other.nightWindow)
                && this.zone.equals(other.zone);
    }
}
