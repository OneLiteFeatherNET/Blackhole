package net.onelitefeather.blackhole.backend.punishment;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.profile.ProfileCache;
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
    private final SectionService sectionService;
    private final DomainEventPublisher eventPublisher;
    private final ProfileCache profileCache;
    private final Clock clock;

    public ActivePunishmentExpirer(
            PunishmentProfileRepository profileRepository,
            SectionService sectionService,
            DomainEventPublisher eventPublisher,
            ProfileCache profileCache,
            Clock clock
    ) {
        this.profileRepository = profileRepository;
        this.sectionService = sectionService;
        this.eventPublisher = eventPublisher;
        this.profileCache = profileCache;
        this.clock = clock;
    }

    /**
     * Whether a punishment (by its metadata) has expired now.
     */
    public boolean isExpired(Map<String, Object> metaData) {
        return PunishmentExpiry.isExpired(metaData, this.clock.millis());
    }

    /**
     * Rotates every expired active punishment of {@code profile} into its history, saves the
     * profile, evicts its cached copy and publishes {@code punishment.expired} per punishment.
     * {@code profile} is mutated in place.
     *
     * @return {@code true} if anything expired
     */
    public boolean expire(PunishmentProfileEntity profile) {
        long now = this.clock.millis();
        List<PunishmentEntity> expired = new ArrayList<>();
        profile.getActive().entrySet().removeIf(entry -> {
            if (PunishmentExpiry.isExpired(entry.getValue(), now)) {
                expired.add(entry.getValue());
                return true;
            }
            return false;
        });
        if (expired.isEmpty()) {
            return false;
        }
        profile.getHistory().addAll(expired);
        this.profileRepository.update(profile);
        this.profileCache.evict(profile.getOwner());
        for (PunishmentEntity punishment : expired) {
            SectionDTO.Response section = this.sectionService.find(punishment.getSectionKey()).orElse(null);
            this.eventPublisher.publish("punishment.expired", PunishmentEvents.expired(punishment, section));
        }
        return true;
    }
}
