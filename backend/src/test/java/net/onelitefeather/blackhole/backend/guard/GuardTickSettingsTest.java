package net.onelitefeather.blackhole.backend.guard;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.guard.dto.PresenceDTO;
import net.onelitefeather.blackhole.backend.support.RecordingEventTransport;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The scheduled tick reads the stored settings and reloads the replica's level, with in-memory fakes and a fixed clock. */
class GuardTickSettingsTest {

    /** 12:00 in Berlin, outside the night window. */
    private static final Instant NOON = Instant.parse("2026-10-29T11:00:00Z");
    private static final GuardSettings CONFIG = GuardSettings.of("STRICT", Duration.ofMinutes(20), "01:00-07:00", "Europe/Berlin",
            Duration.ofMinutes(5));

    private static GuardTickJob tickJob(GuardStateStore states, GuardStateCache cache, GuardSettingsStore settings, Clock clock,
                                        InMemoryPresenceStore presenceStore) {
        GuardPresenceService presence = new GuardPresenceService(presenceStore, CONFIG, clock, cache);
        GuardTransitionService transitions = new GuardTransitionService(states, new InMemoryGuardChangeStore(), presence,
                new UnattendedEvaluator(), settings, new DomainEventPublisher(new RecordingEventTransport(), clock), cache, clock);
        return new GuardTickJob(transitions, cache, new SimpleMeterRegistry());
    }

    @Test
    void aSettingSavedBetweenTwoTicksIsAppliedByTheSecondTick() {
        InMemoryPresenceStore presenceStore = new InMemoryPresenceStore();
        InMemoryGuardStateStore states = new InMemoryGuardStateStore(GuardState.initial(0L));
        GuardStateCache cache = new GuardStateCache(states);
        InMemoryGuardSettingsStore settings = InMemoryGuardSettingsStore.configured();
        Clock seenAt = Clock.fixed(NOON, ZoneOffset.UTC);
        new GuardPresenceService(presenceStore, CONFIG, seenAt, cache).report(new PresenceDTO.Heartbeat("velocity-1", 1));
        // eight minutes later, staff are no longer on a fresh heartbeat
        Clock eightMinutesLater = Clock.fixed(NOON.plus(Duration.ofMinutes(8)), ZoneOffset.UTC);
        GuardTickJob job = tickJob(states, cache, settings, eightMinutesLater, presenceStore);

        job.tick();
        assertEquals(GuardLevel.OFF, states.current().orElseThrow().effective(),
                "eight minutes of absence is below the stored twenty minute threshold, so the first tick keeps OFF");

        settings.save(new UnattendedSettings(GuardLevel.STRICT, Duration.ofMinutes(5), true, NightWindow.parse("01:00-07:00"),
                ZoneId.of("Europe/Berlin"), eightMinutesLater.millis(), null));
        job.tick();

        assertEquals(GuardLevel.STRICT, states.current().orElseThrow().effective(),
                "the second tick must apply the saved five minute threshold without waiting for the snapshot poll");
    }

    @Test
    void theTickReloadsTheReplicaLevelInTheSameTick() {
        InMemoryGuardStateStore states = new InMemoryGuardStateStore(GuardState.of(GuardLevel.OFF, GuardLevel.STRICT, 0L));
        GuardStateCache cache = new GuardStateCache(states);
        assertEquals(GuardLevel.OFF, cache.effective(), "a new replica starts from OFF until it reloads");

        tickJob(states, cache, InMemoryGuardSettingsStore.configured(), Clock.fixed(NOON, ZoneOffset.UTC),
                new InMemoryPresenceStore()).tick();

        assertEquals(GuardLevel.STRICT, cache.effective(),
                "the tick must reload the effective level of this replica, so a row changed elsewhere is in force at once");
    }

    @Test
    void aFailingSettingsReadKeepsTheLevelInUse() {
        InMemoryGuardStateStore states = new InMemoryGuardStateStore(GuardState.of(GuardLevel.OFF, GuardLevel.STRICT, 0L));
        GuardStateCache cache = new GuardStateCache(states);
        cache.reload();
        GuardSettingsStore unreadable = new GuardSettingsStore() {
            @Override
            public UnattendedSettings current() {
                throw new IllegalStateException("the settings row cannot be read");
            }

            @Override
            public void save(UnattendedSettings settings) {
                throw new IllegalStateException("the settings row cannot be written");
            }
        };

        assertDoesNotThrow(() -> tickJob(states, cache, unreadable, Clock.fixed(NOON, ZoneOffset.UTC), new InMemoryPresenceStore()).tick(),
                "a failing settings read must not escape the scheduled tick");
        assertEquals(GuardLevel.STRICT, cache.effective(), "a failing settings read must keep the level in use");
    }
}
