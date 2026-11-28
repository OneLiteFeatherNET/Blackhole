package net.onelitefeather.blackhole.backend.security;

import net.onelitefeather.blackhole.backend.support.StaffDelegations;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkEntity;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkKind;
import net.onelitefeather.blackhole.backend.discord.DiscordLinkRepository;
import net.onelitefeather.blackhole.backend.support.InMemoryRepository;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** The DISCORD bot may act for a linked staff member only with a live link and a fresh MODERATOR or ADMIN snapshot. */
class DiscordOnBehalfTest {

    private static final UUID STAFF = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID BOT = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Duration WINDOW = Duration.ofHours(24);
    private static final Actor BOT_ACTOR = new Actor(BOT, "Blackhole Discord", ActorKind.SERVICE, false, false, false, true);

    private final TestClock clock = new TestClock();
    private final List<DiscordLinkEntity> linkRows = new ArrayList<>();
    private final InMemoryRepository<DiscordLinkRepository, DiscordLinkEntity, UUID> links =
            InMemoryRepository.<DiscordLinkRepository, DiscordLinkEntity, UUID>of(DiscordLinkRepository.class, DiscordLinkEntity::getId)
                    .finder("findByKindAndSubject", args -> this.linkRows.stream()
                            .filter(link -> link.getKind() == args[0] && link.getSubject().equals(args[1])).findFirst())
                    .build();
    private final InMemoryRepository<StaffActorRepository, StaffActorEntity, UUID> actors =
            InMemoryRepository.<StaffActorRepository, StaffActorEntity, UUID>of(StaffActorRepository.class, StaffActorEntity::getOid).build();
    private final StaffDelegation delegation = new StaffDelegation(this.links.repository(), this.actors.repository(), StaffDelegations.emptyStaffLinks(), this.clock, WINDOW);

    private void linked() {
        this.linkRows.add(new DiscordLinkEntity(UUID.randomUUID(), DiscordLinkKind.STAFF, STAFF.toString(), "123456789012345678", 1L));
    }

    private void snapshot(String roles, long seenAt) {
        StaffActorEntity actor = new StaffActorEntity(STAFF, ActorKind.HUMAN, "Alex", 1L);
        actor.setRoles(roles);
        actor.setRolesSeenAt(seenAt);
        this.actors.repository().save(actor);
    }

    @Test
    void aLinkedMemberWithAFreshModeratorSnapshotIsAttributedToThatMemberAndRecordedByTheBot() {
        linked();
        snapshot("MODERATOR", this.clock.millis());

        StaffDelegation.Outcome outcome = this.delegation.attribute(BOT_ACTOR, STAFF);

        Attribution attribution = assertInstanceOf(StaffDelegation.Outcome.Allowed.class, outcome).attribution();
        assertEquals(STAFF, attribution.source(), "the staff member is the actor");
        assertEquals(BOT, attribution.recordedByOid(), "the bot is the recording principal");
    }

    @Test
    void aMemberWithNoLiveStaffLinkIsNotEligible() {
        snapshot("MODERATOR", this.clock.millis());

        StaffDelegation.Outcome.Refused refused = assertInstanceOf(StaffDelegation.Outcome.Refused.class, this.delegation.attribute(BOT_ACTOR, STAFF));

        assertEquals(StaffDelegation.Reason.NOT_ELIGIBLE, refused.reason(), "no link, no delegation");
    }

    @Test
    void aSnapshotOlderThanTheWindowIsStale() {
        linked();
        snapshot("MODERATOR", this.clock.millis() - WINDOW.toMillis() - 1000L);

        StaffDelegation.Outcome.Refused refused = assertInstanceOf(StaffDelegation.Outcome.Refused.class, this.delegation.attribute(BOT_ACTOR, STAFF));

        assertEquals(StaffDelegation.Reason.ROLE_STALE, refused.reason(), "older than PT24H plus one second");
    }

    @Test
    void aSnapshotExactlyAtTheWindowIsStillFresh() {
        linked();
        snapshot("MODERATOR", this.clock.millis() - WINDOW.toMillis());

        assertInstanceOf(StaffDelegation.Outcome.Allowed.class, this.delegation.attribute(BOT_ACTOR, STAFF),
                "the window is inclusive");
    }

    @Test
    void aViewerSnapshotIsNotEligible() {
        linked();
        snapshot("VIEWER", this.clock.millis());

        StaffDelegation.Outcome.Refused refused = assertInstanceOf(StaffDelegation.Outcome.Refused.class, this.delegation.attribute(BOT_ACTOR, STAFF));

        assertEquals(StaffDelegation.Reason.NOT_ELIGIBLE, refused.reason(), "VIEWER is not MODERATOR or ADMIN");
    }

    @Test
    void theBotWithoutOnBehalfOfIsRefusedOnADelegationRoute() {
        StaffDelegation.Outcome.Refused refused = assertInstanceOf(StaffDelegation.Outcome.Refused.class, this.delegation.attribute(BOT_ACTOR, null));

        assertEquals(StaffDelegation.Reason.REQUIRED, refused.reason(), "the bot cannot act as itself on a decision");
    }

    @Test
    void onBehalfOfFromAHumanIsRefused() {
        Actor moderator = new Actor(STAFF, "Alex", ActorKind.HUMAN, false, false, false, false);

        StaffDelegation.Outcome.Refused refused = assertInstanceOf(StaffDelegation.Outcome.Refused.class, this.delegation.attribute(moderator, BOT));

        assertEquals(StaffDelegation.Reason.NOT_DISCORD, refused.reason(), "only the bot may name a staff member");
    }
}
