package net.onelitefeather.blackhole.backend.guard;

import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.guard.dto.GuardDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.support.RecordingEventTransport;
import net.onelitefeather.blackhole.backend.support.StaffDelegations;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** A manual guard level set by the proxy or the bot: the source and the actor it is recorded with. */
class GuardLevelDelegationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-29T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID STAFF = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    private static final UUID MINECRAFT = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
    private static final UUID PROXY = UUID.fromString("00000000-0000-4000-8000-0000000000c2");

    private final InMemoryGuardStateStore states = new InMemoryGuardStateStore(GuardState.initial(0L));
    private final InMemoryGuardChangeStore changes = new InMemoryGuardChangeStore();
    private final StaffDelegations.Linked linked = StaffDelegations.linked(CLOCK);

    private GuardService service() {
        GuardStateCache cache = new GuardStateCache(this.states);
        GuardSettings settings = GuardSettings.of("STRICT", Duration.ofMinutes(20), "01:00-07:00", "Europe/Berlin",
                Duration.ofMinutes(5));
        GuardPresenceService presence = new GuardPresenceService(new InMemoryPresenceStore(), settings, CLOCK, cache);
        GuardTransitionService transitions = new GuardTransitionService(this.states, this.changes, presence,
                new UnattendedEvaluator(), InMemoryGuardSettingsStore.configured(),
                new DomainEventPublisher(new RecordingEventTransport(), CLOCK), cache, CLOCK);
        return new GuardService(this.states, transitions, this.linked.delegation(), StaffDelegations.recorder(CLOCK));
    }

    private static Actor proxy() {
        return new Actor(PROXY, "proxy", ActorKind.SERVICE, true, false, false, false);
    }

    @Test
    void aLevelSetThroughTheProxyIsRecordedWithTheDelegatedStaffMemberAndTheInGameSource() {
        this.linked.givenInGameStaff(STAFF, MINECRAFT);
        this.linked.givenSnapshot(STAFF, "MODERATOR", CLOCK.millis());

        GuardDTO result = service().setLevel(new GuardDTO.CreateRequest("STRICT", MINECRAFT), proxy());

        assertInstanceOf(GuardDTO.Response.class, result, "a delegated level must be set");
        assertEquals(GuardChange.Source.IN_GAME, this.changes.changes().getFirst().source(), "the source must be the in-game surface");
        assertEquals(MINECRAFT.toString(), this.changes.changes().getFirst().actorOid(),
                "the change carries the in-game account the proxy vouches for, as punishments do");
    }

    @Test
    void aMachineWithoutAStaffMemberIsRefusedAndNothingChanges() {
        GuardDTO result = service().setLevel(new GuardDTO.CreateRequest("LOCKDOWN"), proxy());

        assertEquals(ErrorKind.FORBIDDEN, assertInstanceOf(GuardDTO.Error.class, result, "a machine without onBehalfOf is refused").kind(),
                "the refusal is the same as the Discord bot's");
        assertEquals(0, this.changes.changes().size(), "a refused delegation must not change the level");
    }
}
