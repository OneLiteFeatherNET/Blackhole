package net.onelitefeather.blackhole.backend.security;

import net.onelitefeather.blackhole.backend.discord.DiscordLinkEntity;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkKind;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkRepository;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bot's staff lookup: the oid of the live staff link of a Discord user, and whether a press would be allowed now. */
class StaffLinkLookupTest {

    private static final UUID STAFF = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String DISCORD_USER = "123456789012345678";

    private final TestClock clock = new TestClock();
    private final List<DiscordLinkEntity> linkRows = new ArrayList<>();
    private final InMemoryRepository<DiscordLinkRepository, DiscordLinkEntity, UUID> links =
            InMemoryRepository.<DiscordLinkRepository, DiscordLinkEntity, UUID>of(DiscordLinkRepository.class, DiscordLinkEntity::getId)
                    .finder("findByKindAndDiscordUserId", args -> this.linkRows.stream()
                            .filter(link -> link.getKind() == args[0] && link.getDiscordUserId().equals(args[1])).findFirst())
                    .build();
    private final InMemoryRepository<StaffActorRepository, StaffActorEntity, UUID> actors =
            InMemoryRepository.<StaffActorRepository, StaffActorEntity, UUID>of(StaffActorRepository.class, StaffActorEntity::getOid).build();
    private final StaffDelegation delegation = new StaffDelegation(this.links.repository(), this.actors.repository(), this.clock,
            Duration.ofHours(24));

    private void linked() {
        this.linkRows.add(new DiscordLinkEntity(UUID.randomUUID(), DiscordLinkKind.STAFF, STAFF.toString(), DISCORD_USER, 1L));
    }

    private void snapshot(String roles, long seenAt) {
        StaffActorEntity actor = new StaffActorEntity(STAFF, ActorKind.HUMAN, "Alex", 1L);
        actor.setRoles(roles);
        actor.setRolesSeenAt(seenAt);
        this.actors.repository().save(actor);
    }

    @Test
    void aLinkedMemberWithAFreshModeratorSnapshotIsEligible() {
        linked();
        snapshot("MODERATOR", this.clock.millis());

        Optional<StaffDelegation.Lookup> found = this.delegation.lookup(DISCORD_USER);

        assertEquals(STAFF, found.orElseThrow().oid(), "the staff oid is answered");
        assertTrue(found.get().eligible(), "a press would be allowed now");
    }

    @Test
    void aLinkedMemberWithoutASnapshotIsNotEligible() {
        linked();

        Optional<StaffDelegation.Lookup> found = this.delegation.lookup(DISCORD_USER);

        assertFalse(found.orElseThrow().eligible(), "no snapshot, no press");
    }

    @Test
    void aDiscordUserWithoutALiveLinkIsAbsent() {
        assertEquals(Optional.empty(), this.delegation.lookup(DISCORD_USER), "404 at the route");
    }

    @Test
    void aStaleSnapshotIsNotEligible() {
        linked();
        snapshot("MODERATOR", this.clock.millis() - Duration.ofHours(25).toMillis());

        assertFalse(this.delegation.lookup(DISCORD_USER).orElseThrow().eligible(), "older than the window");
    }
}
