package net.onelitefeather.blackhole.backend.guard;

/** The single row of the unattended settings. The first read seeds the row from the configuration. */
public interface GuardSettingsStore {

    /** The stored settings. A missing row is seeded from blackhole.guard.unattended.* first. */
    UnattendedSettings current();

    /** Replaces the stored settings; the caller has checked them with {@link GuardSettingsRules}. */
    void save(UnattendedSettings settings);
}
