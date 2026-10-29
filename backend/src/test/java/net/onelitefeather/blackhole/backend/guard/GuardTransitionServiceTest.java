package net.onelitefeather.blackhole.backend.guard;

import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.guard.dto.PresenceDTO;
import net.onelitefeather.blackhole.backend.support.RecordingEventTransport;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardTransitionServiceTest {

    /** 12:00 in Berlin, outside the night window. */
    private static final Instant NOON = Instant.parse("2026-10-29T11:00:00Z");

    private final Clock clock = Clock.fixed(NOON, ZoneOffset.UTC);
    private final GuardSettings settings = GuardSettings.of("STRICT", Duration.ofMinutes(20), "01:00-07:00", "Europe/Berlin",
            Duration.ofMinutes(5));
    private final InMemoryPresenceStore presenceStore = new InMemoryPresenceStore();
    private final GuardPresenceService presence = new GuardPresenceService(this.presenceStore, this.settings, this.clock,
            new GuardStateCache(new InMemoryGuardStateStore(GuardState.initial(0L))));
    private final InMemoryGuardChangeStore changes = new InMemoryGuardChangeStore();
    private final RecordingEventTransport transport = new RecordingEventTransport();
    private final DomainEventPublisher events = new DomainEventPublisher(this.transport, this.clock);

    private GuardTransitionService service(GuardStateStore states) {
        return new GuardTransitionService(states, this.changes, this.presence, new UnattendedEvaluator(this.settings),
                this.events, new GuardStateCache(states), this.clock);
    }

    @Test
    void theUnattendedLevelIsRaisedWhenStaffHaveBeenAbsentLongEnough() {
        InMemoryGuardStateStore states = new InMemoryGuardStateStore(GuardState.initial(0L));

        boolean changed = service(states).reconcileAutomatic();

        assertTrue(changed, "with no staff ever seen the unattended level must be raised");
        assertEquals(GuardLevel.STRICT, states.current().orElseThrow().effective(),
                "the effective level must become STRICT once the unattended level is raised");
    }

    @Test
    void aSecondReplicaSeeingTheChangedRowWritesNothing() {
        InMemoryGuardStateStore states = new InMemoryGuardStateStore(GuardState.initial(0L));
        GuardTransitionService first = service(states);
        GuardTransitionService second = service(states);

        boolean firstChanged = first.reconcileAutomatic();
        boolean secondChanged = second.reconcileAutomatic();

        assertTrue(firstChanged, "the first replica must write the transition");
        assertFalse(secondChanged, "the second replica must see the row already changed and write nothing");
        assertEquals(1, this.changes.changes().size(), "exactly one history row must be written for one transition");
        assertEquals(1, this.transport.published().size(), "exactly one level change event must be published");
    }

    @Test
    void theLevelChangeEventCarriesOnlyTheLevelAndTheSource() {
        InMemoryGuardStateStore states = new InMemoryGuardStateStore(GuardState.initial(0L));

        service(states).reconcileAutomatic();

        RecordingEventTransport.Published published = this.transport.published().getFirst();
        assertEquals("guard.level_changed", published.eventType(), "the event must be the level change");
        assertEquals("STRICT", published.event().payload().get("level"), "the payload must carry the new level");
        assertEquals("UNATTENDED", published.event().payload().get("source"), "the payload must carry the source");
        assertFalse(published.event().payload().containsKey("ip"), "the payload must never carry an address");
    }

    @Test
    void staffReturningClearsTheUnattendedLevelAtOnce() {
        InMemoryGuardStateStore states = new InMemoryGuardStateStore(GuardState.of(GuardLevel.OFF, GuardLevel.STRICT, 0L));
        this.presence.report(new PresenceDTO.Heartbeat("velocity-1", 1));

        boolean changed = service(states).reconcileAutomatic();

        assertTrue(changed, "a heartbeat with staff must clear the unattended level on the next tick");
        assertEquals(GuardLevel.OFF, states.current().orElseThrow().effective(), "the effective level must return to OFF");
        assertEquals(GuardChange.Source.CLEARED, this.changes.changes().getFirst().source(),
                "the history must record that the unattended level was cleared");
    }

    @Test
    void aManualLockdownIsNotUndoneWhenStaffComeOnline() {
        InMemoryGuardStateStore states = new InMemoryGuardStateStore(GuardState.of(GuardLevel.LOCKDOWN, GuardLevel.STRICT, 0L));
        this.presence.report(new PresenceDTO.Heartbeat("velocity-1", 1));

        service(states).reconcileAutomatic();

        assertEquals(GuardLevel.LOCKDOWN, states.current().orElseThrow().effective(),
                "a manual LOCKDOWN must hold while staff are online, because only the manual level sets it");
        assertEquals(GuardLevel.LOCKDOWN, states.current().orElseThrow().manual(), "the manual level must be untouched");
    }

    @Test
    void settingTheManualLevelRecordsTheActorAndPublishes() {
        InMemoryGuardStateStore states = new InMemoryGuardStateStore(GuardState.initial(0L));

        Optional<GuardState> result = service(states).setManual(GuardLevel.STRICT, "actor-oid");

        assertEquals(GuardLevel.STRICT, result.orElseThrow().effective(), "the manual STRICT must be in force");
        assertEquals("actor-oid", this.changes.changes().getFirst().actorOid(), "the history must name the actor");
        assertEquals(GuardChange.Source.MANUAL, this.changes.changes().getFirst().source(), "the source must be manual");
        assertEquals(1, this.transport.published().size(), "a manual change must publish one level change event");
    }

    @Test
    void settingTheSameManualLevelAgainWritesNoHistory() {
        InMemoryGuardStateStore states = new InMemoryGuardStateStore(GuardState.of(GuardLevel.STRICT, GuardLevel.OFF, 0L));

        service(states).setManual(GuardLevel.STRICT, "actor-oid");

        assertTrue(this.changes.changes().isEmpty(), "setting the level it already has must not write a history row");
        assertTrue(this.transport.published().isEmpty(), "setting the level it already has must not publish");
    }
}
