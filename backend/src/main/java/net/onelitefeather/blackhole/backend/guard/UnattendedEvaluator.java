package net.onelitefeather.blackhole.backend.guard;

import jakarta.inject.Singleton;

import java.time.Instant;
import java.time.LocalTime;
import java.util.OptionalLong;

/**
 * Decides the unattended level from the stored settings, the staff presence and the clock. Pure: it reads nothing itself,
 * so the caller passes the settings, the presence and the time. Staff on the proxy always mean OFF. Otherwise the unattended
 * level applies when staff have been absent for the threshold, or when the night window is enabled and the local time is
 * inside it.
 */
@Singleton
public class UnattendedEvaluator {

    /**
     * @param settings      the stored unattended settings, read on every tick
     * @param staffPresent  whether a fresh proxy heartbeat reported staff online
     * @param lastStaffSeen when a proxy last reported staff online, or empty if never; empty counts as long absent
     * @param nowMillis     the current time, from the injected clock
     */
    public GuardLevel automaticLevel(UnattendedSettings settings, boolean staffPresent, OptionalLong lastStaffSeen, long nowMillis) {
        if (staffPresent) {
            return GuardLevel.OFF;
        }
        boolean longAbsent = lastStaffSeen.isEmpty()
                || nowMillis - lastStaffSeen.getAsLong() >= settings.absenceThreshold().toMillis();
        LocalTime localTime = Instant.ofEpochMilli(nowMillis).atZone(settings.zone()).toLocalTime();
        boolean inNight = settings.nightWindowEnabled() && settings.nightWindow().contains(localTime);
        return longAbsent || inNight ? settings.unattendedLevel() : GuardLevel.OFF;
    }
}
