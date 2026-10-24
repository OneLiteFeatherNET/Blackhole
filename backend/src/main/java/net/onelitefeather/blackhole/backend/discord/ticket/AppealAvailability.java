package net.onelitefeather.blackhole.backend.discord.ticket;

import java.util.Collection;
import java.util.OptionalLong;

/**
 * When a player may file an appeal, for the reply that names the date. It mirrors the two hard checks of the eligibility checklist
 * ({@code AppealEligibilityService}): the punishment must be older than the minimum, and the newest earlier denied or ineligible appeal
 * must be older than the repeat-appeal cooldown.
 */
public final class AppealAvailability {

    private static final long DAY_MILLIS = 86_400_000L;

    private AppealAvailability() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * @param punishmentCreatedAt   epoch millis the punishment was applied
     * @param minDaysRequired       the checklist's minimum age in days
     * @param priorAppealCreatedAts when the earlier denied or ineligible appeals of the punishment were filed
     * @param cooldownDays          the repeat-appeal cooldown in days
     * @return the epoch millis from which filing is possible, empty when it is possible now
     */
    public static OptionalLong availableFrom(long now, long punishmentCreatedAt, int minDaysRequired, Collection<Long> priorAppealCreatedAts,
                                             int cooldownDays) {
        long from = punishmentCreatedAt + minDaysRequired * DAY_MILLIS;
        for (long prior : priorAppealCreatedAts) {
            from = Math.max(from, prior + cooldownDays * DAY_MILLIS);
        }
        return from > now ? OptionalLong.of(from) : OptionalLong.empty();
    }
}
