package net.onelitefeather.blackhole.backend.guard;

import java.util.Map;

/**
 * The guard.settings_changed payload. It is a whitelist: the stored settings and the change time. It never carries the
 * actor's name, an address, an owner hash or a player name.
 */
public final class GuardSettingsEvents {

    public static final String CHANGED = "guard.settings_changed";

    private GuardSettingsEvents() {
    }

    public static Map<String, Object> changed(UnattendedSettings saved) {
        return Map.of(
                "unattendedLevel", saved.unattendedLevel().name(),
                "absenceThresholdMinutes", saved.absenceThreshold().toMinutes(),
                "nightWindowEnabled", saved.nightWindowEnabled(),
                "nightWindowStart", saved.nightWindow().start().toString(),
                "nightWindowEnd", saved.nightWindow().end().toString(),
                "timeZone", saved.zone().getId(),
                "changedAt", saved.updatedAt(),
                "source", "PANEL");
    }
}
