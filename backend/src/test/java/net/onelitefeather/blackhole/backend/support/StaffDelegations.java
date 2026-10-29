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
import net.onelitefeather.blackhole.backend.security.StaffLinkEntity;
import net.onelitefeather.blackhole.backend.security.StaffLinkRepository;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Fixtures for the Discord delegation: a {@link StaffDelegation} with no live links, one with a test-controlled link table, and recorders. */
public final class StaffDelegations {

    private StaffDelegations() {
    }

    /** A delegation over empty link and staff tables: every delegated press is refused as not eligible. */
    public static StaffDelegation withoutLinks(Clock clock) {
        return linked(clock).delegation();
    }

    /** An empty staff link table, for a delegation that only the Discord bot uses. */
    public static StaffLinkRepository emptyStaffLinks() {
        return InMemoryRepository.<StaffLinkRepository, StaffLinkEntity, UUID>of(StaffLinkRepository.class, StaffLinkEntity::getOid)
                .build().repository();
    }

    /**
     * A delegation whose link and staff tables the test fills: {@link Linked#links()} holds the live Discord links, {@link Linked#actors()}
     * the staff rows with their role snapshots.
     */
    public static Linked linked(Clock clock) {
        List<DiscordLinkEntity> linkRows = new ArrayList<>();
        InMemoryRepository<DiscordLinkRepository, DiscordLinkEntity, UUID> links =
                InMemoryRepository.<DiscordLinkRepository, DiscordLinkEntity, UUID>of(DiscordLinkRepository.class, DiscordLinkEntity::getId)
                        .finder("findByKindAndSubject", args -> linkRows.stream()
                                .filter(link -> link.getKind() == args[0] && link.getSubject().equals(args[1])).findFirst())
                        .finder("findByKindAndDiscordUserId", args -> linkRows.stream()
                                .filter(link -> link.getKind() == args[0] && link.getDiscordUserId().equals(args[1])).findFirst())
                        .build();
        InMemoryRepository<StaffActorRepository, StaffActorEntity, UUID> actors =
                InMemoryRepository.<StaffActorRepository, StaffActorEntity, UUID>of(StaffActorRepository.class, StaffActorEntity::getOid).build();
        List<StaffLinkEntity> staffLinkRows = new ArrayList<>();
        InMemoryRepository<StaffLinkRepository, StaffLinkEntity, UUID> staffLinks =
                InMemoryRepository.<StaffLinkRepository, StaffLinkEntity, UUID>of(StaffLinkRepository.class, StaffLinkEntity::getOid)
                        .finder("findByMinecraftUuid", args -> staffLinkRows.stream()
                                .filter(row -> row.getMinecraftUuid().equals(args[0])).findFirst())
                        .build();
        StaffDelegation delegation = new StaffDelegation(links.repository(), actors.repository(), staffLinks.repository(), clock,
                Duration.ofHours(24));
        return new Linked(delegation, actors, linkRows, staffLinks, staffLinkRows);
    }

    /** A recorder over the given in-memory audit store and a recording event transport. */
    public static StaffAuditRecorder recorder(Clock clock, InMemoryStaffAuditStore store) {
        return new StaffAuditRecorder(store, new DomainEventPublisher(new RecordingEventTransport(), clock),
                new AuditPayloadGuard(), clock, new SimpleMeterRegistry());
    }

    /** A recorder over a fresh in-memory audit store. */
    public static StaffAuditRecorder recorder(Clock clock) {
        return recorder(clock, new InMemoryStaffAuditStore());
    }

    /** The parts of a {@link #linked} delegation a test fills in. */
    public record Linked(StaffDelegation delegation, InMemoryRepository<StaffActorRepository, StaffActorEntity, UUID> actors,
                         List<DiscordLinkEntity> links, InMemoryRepository<StaffLinkRepository, StaffLinkEntity, UUID> staffLinks,
                         List<StaffLinkEntity> staffLinkRows) {

        /** Links a staff member to a Minecraft account, the live staff link the proxy's delegation checks. */
        public void givenInGameStaff(UUID oid, UUID minecraftUuid) {
            StaffLinkEntity row = new StaffLinkEntity(oid, minecraftUuid, 1L, oid);
            this.staffLinkRows.add(row);
            this.staffLinks.repository().save(row);
        }

        /** Gives a staff member a fresh snapshot with the given roles, taken at {@code seenAt}, without any link. */
        public void givenSnapshot(UUID oid, String roles, long seenAt) {
            StaffActorEntity row = new StaffActorEntity(oid, net.onelitefeather.blackhole.backend.security.ActorKind.HUMAN, "Staff", 1L);
            row.setRoles(roles);
            row.setRolesSeenAt(seenAt);
            this.actors.repository().save(row);
        }

        /** Links a staff member to a Discord account and gives them a fresh snapshot with the given roles, taken at {@code seenAt}. */
        public void givenLinkedStaff(UUID oid, String discordUserId, String roles, long seenAt) {
            this.links.add(new DiscordLinkEntity(UUID.randomUUID(), net.onelitefeather.blackhole.backend.discord.DiscordLinkKind.STAFF,
                    oid.toString(), discordUserId, 1L));
            StaffActorEntity row = new StaffActorEntity(oid, net.onelitefeather.blackhole.backend.security.ActorKind.HUMAN, "Staff", 1L);
            row.setRoles(roles);
            row.setRolesSeenAt(seenAt);
            this.actors.repository().save(row);
        }
    }
}
