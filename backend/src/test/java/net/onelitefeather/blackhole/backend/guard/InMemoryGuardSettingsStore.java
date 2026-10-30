package net.onelitefeather.blackhole.backend.guard;

import java.time.Duration;
import java.time.ZoneId;

/** A {@link GuardSettingsStore} held in memory, with the seeded defaults of the application. */
public class InMemoryGuardSettingsStore implements GuardSettingsStore {

    private UnattendedSettings settings;

    public InMemoryGuardSettingsStore(UnattendedSettings initial) {
        this.settings = initial;
    }

    /** The defaults of application.yml: STRICT, 20 minutes, 01:00-07:00 enabled, Europe/Berlin, never saved by an admin. */
    public static InMemoryGuardSettingsStore configured() {
        return new InMemoryGuardSettingsStore(new UnattendedSettings(GuardLevel.STRICT, Duration.ofMinutes(20), true,
                NightWindow.parse("01:00-07:00"), ZoneId.of("Europe/Berlin"), 0L, null));
    }

    @Override
    public synchronized UnattendedSettings current() {
        return this.settings;
    }

    @Override
    public synchronized void save(UnattendedSettings next) {
        this.settings = next;
    }
}
