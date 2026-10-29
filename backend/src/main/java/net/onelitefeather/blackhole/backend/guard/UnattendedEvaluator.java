package net.onelitefeather.blackhole.backend.guard;

import jakarta.inject.Singleton;

import java.time.Instant;
import java.time.LocalTime;
import java.util.OptionalLong;

/**
 * Decides the unattended level from the staff presence and the clock. Pure: it reads nothing itself, so the caller
 * passes the presence and the time. Staff on the proxy always mean OFF. Otherwise the unattended level applies when
 * staff have been absent for the threshold, or when the local time is inside the night window.
 */
@Singleton
public class UnattendedEvaluator {

    private final GuardSettings settings;

    public UnattendedEvaluator(GuardSettings settings) {
        this.settings = settings;
    }

    /**
     * @param staffPresent  whether a fresh proxy heartbeat reported staff online
     * @param lastStaffSeen when a proxy last reported staff online, or empty if never; empty counts as long absent
     * @param nowMillis     the current time, from the injected clock
     */
    public GuardLevel automaticLevel(boolean staffPresent, OptionalLong lastStaffSeen, long nowMillis) {
        if (staffPresent) {
            return GuardLevel.OFF;
        }
        boolean longAbsent = lastStaffSeen.isEmpty()
                || nowMillis - lastStaffSeen.getAsLong() >= this.settings.absenceThreshold().toMillis();
        LocalTime localTime = Instant.ofEpochMilli(nowMillis).atZone(this.settings.zone()).toLocalTime();
        boolean inNight = this.settings.nightWindow().contains(localTime);
        return longAbsent || inNight ? this.settings.unattendedLevel() : GuardLevel.OFF;
    }
}
