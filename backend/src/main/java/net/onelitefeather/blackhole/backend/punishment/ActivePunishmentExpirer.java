package net.onelitefeather.blackhole.backend.punishment;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.casetimeline.CaseTimeline;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.profile.ProfileCache;
import net.onelitefeather.blackhole.backend.profile.ProfileSlots;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileRepository;
import net.onelitefeather.blackhole.backend.section.SectionService;
import net.onelitefeather.blackhole.backend.section.dto.SectionDTO;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Moves expired active punishments into a profile's history. Shared by the scheduled sweep and by
 * the profile read, so both rotate the same way and both publish {@code punishment.expired} with the
 * punishment's section - which is what clears the right Redis key and tells proxies what ended.
 */
@Singleton
public class ActivePunishmentExpirer {

    private final PunishmentProfileRepository profileRepository;
    private final ProfileSlots slots;
    private final SectionService sectionService;
    private final DomainEventPublisher eventPublisher;
    private final ProfileCache profileCache;
    private final CaseTimeline caseTimeline;
    private final Clock clock;

    public ActivePunishmentExpirer(
            PunishmentProfileRepository profileRepository,
            ProfileSlots slots,
            SectionService sectionService,
            DomainEventPublisher eventPublisher,
            ProfileCache profileCache,
            CaseTimeline caseTimeline,
            Clock clock
    ) {
        this.profileRepository = profileRepository;
        this.slots = slots;
        this.sectionService = sectionService;
        this.eventPublisher = eventPublisher;
        this.profileCache = profileCache;
        this.caseTimeline = caseTimeline;
        this.clock = clock;
    }

    /**
     * Whether a punishment (by its metadata) has expired now.
     */
    public boolean isExpired(Map<String, Object> metaData) {
        return PunishmentExpiry.isExpired(metaData, this.clock.millis());
    }

    /**
     * Rotates every expired active punishment of {@code profile} into its history, evicts its cached
     * copy and publishes {@code punishment.expired} per punishment. Each slot is cleared on its own
     * row and only while it still holds the expired punishment, so a punishment applied concurrently
     * to the same section is never removed. {@code profile} is updated in place to reflect the result.
     *
     * @return {@code true} if anything expired
     */
    public boolean expire(PunishmentProfileEntity profile) {
        long now = this.clock.millis();
        String owner = profile.getOwner();
        List<PunishmentEntity> expired = new ArrayList<>();
        boolean lostRace = false;
        for (Map.Entry<String, PunishmentEntity> entry : List.copyOf(profile.getActive().entrySet())) {
            if (!entry.getValue().canBeActive() || !PunishmentExpiry.isExpired(entry.getValue(), now)) {
                continue;
            }
            if (this.slots.clearIf(owner, entry.getKey(), entry.getValue().getIdentifier())) {
                expired.add(entry.getValue());
            } else {
                lostRace = true;
            }
        }
        if (lostRace) {
            // The slot changed under us; show what is stored now instead of our stale copy.
            this.profileRepository.findById(owner).ifPresent(fresh -> {
                profile.getActive().clear();
                profile.getActive().putAll(fresh.getActive());
                profile.getHistory().clear();
                profile.getHistory().addAll(fresh.getHistory());
            });
            this.profileCache.evict(owner);
        } else if (!expired.isEmpty()) {
            expired.forEach(punishment -> profile.getActive().remove(punishment.getSectionKey()));
            expired.stream().filter(punishment -> !profile.getHistory().contains(punishment)).forEach(profile.getHistory()::add);
        }
        if (expired.isEmpty()) {
            return false;
        }
        this.profileCache.evict(owner);
        for (PunishmentEntity punishment : expired) {
            SectionDTO.Response section = this.sectionService.find(punishment.getSectionKey()).orElse(null);
            this.eventPublisher.publish("punishment.expired", PunishmentEvents.expired(punishment, section));
            this.caseTimeline.expired(punishment);
        }
        return true;
    }
}
