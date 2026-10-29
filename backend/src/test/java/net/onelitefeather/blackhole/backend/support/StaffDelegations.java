package net.onelitefeather.blackhole.backend.support;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.audit.AuditPayloadGuard;
import net.onelitefeather.blackhole.backend.audit.StaffAuditRecorder;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkEntity;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkRepository;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.security.StaffActorEntity;
import net.onelitefeather.blackhole.backend.security.StaffActorRepository;
import net.onelitefeather.blackhole.backend.security.StaffDelegation;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Fixtures for the Discord delegation: a {@link StaffDelegation} with no live links, and a recorder over in-memory stores. */
public final class StaffDelegations {

    private StaffDelegations() {
    }

    /** A delegation over empty link and staff tables: every delegated press is refused as not eligible. */
    public static StaffDelegation withoutLinks(Clock clock) {
        InMemoryRepository<DiscordLinkRepository, DiscordLinkEntity, UUID> links =
                InMemoryRepository.<DiscordLinkRepository, DiscordLinkEntity, UUID>of(DiscordLinkRepository.class, DiscordLinkEntity::getId)
                        .finder("findByKindAndSubject", args -> Optional.empty())
                        .finder("findByKindAndDiscordUserId", args -> Optional.empty())
                        .build();
        InMemoryRepository<StaffActorRepository, StaffActorEntity, UUID> actors =
                InMemoryRepository.<StaffActorRepository, StaffActorEntity, UUID>of(StaffActorRepository.class, StaffActorEntity::getOid).build();
        return new StaffDelegation(links.repository(), actors.repository(), clock, Duration.ofHours(24));
    }

    /** An audit recorder over in-memory stores and a recording event transport. */
    public static StaffAuditRecorder recorder(Clock clock) {
        return new StaffAuditRecorder(new InMemoryStaffAuditStore(), new DomainEventPublisher(new RecordingEventTransport(), clock),
                new AuditPayloadGuard(), clock, new SimpleMeterRegistry());
    }
}
