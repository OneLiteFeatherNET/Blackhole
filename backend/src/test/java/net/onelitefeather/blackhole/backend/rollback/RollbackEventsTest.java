package net.onelitefeather.blackhole.backend.rollback;

import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.onelitefeather.blackhole.backend.support.PunishmentTestBed.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollbackEventsTest {

    private static final String REASON = "compromised account, see ticket 12";

    private final RollbackBed world = new RollbackBed();

    private void givenFrozenItemAndStarted() {
        String identifier = world.givenActiveMute(OWNER);
        world.givenPreview(List.of(new RollbackJobStore.FrozenItem(identifier, OWNER, "chat", "PUNISHMENT")));
        assertInstanceOf(RollbackDTO.Started.class, world.execution().start(RollbackBed.JOB, REASON, "key-1"));
    }

    @Test
    void theStartedEventCarriesTheJobAndCountsOnly() {
        givenFrozenItemAndStarted();

        Map<String, Object> payload = world.bed.eloBed.events.eventsOfType(RollbackEvents.STARTED).getFirst().payload();

        assertEquals(Set.of("jobId", "requestedBy", "frozen"), payload.keySet(), "no owner, player or reason in the payload");
        assertEquals(1L, payload.get("frozen"));
        assertFalse(payload.toString().contains(OWNER), "no owner hash");
        assertFalse(payload.toString().contains(REASON), "no reason text");
    }

    @Test
    void theCompletedEventCarriesTheCountsOnly() {
        givenFrozenItemAndStarted();

        world.runner().tick();

        Map<String, Object> payload = world.bed.eloBed.events.eventsOfType(RollbackEvents.COMPLETED).getFirst().payload();
        assertEquals(Set.of("jobId", "requestedBy", "revoked", "skipped", "failed"), payload.keySet());
        assertEquals(1L, payload.get("revoked"));
        assertEquals(0L, payload.get("failed"));
        assertFalse(payload.toString().contains(OWNER), "no owner hash");
        assertFalse(payload.toString().contains(REASON), "no reason text");
    }

    @Test
    void aFailingPublishDoesNotFailTheStart() {
        String identifier = world.givenActiveMute(OWNER);
        world.givenPreview(List.of(new RollbackJobStore.FrozenItem(identifier, OWNER, "chat", "PUNISHMENT")));
        RecordingEventPublisher brokerDown = new RecordingEventPublisher() {
            @Override
            public void publish(String eventType, Map<String, Object> payload) {
                throw new IllegalStateException("broker down");
            }
        };
        var service = new RollbackExecutionService(world.jobs, brokerDown, world.bed.eloBed.clock);

        RollbackDTO result = service.start(RollbackBed.JOB, REASON, "key-1");

        assertTrue(result instanceof RollbackDTO.Started, "the start is kept even though the event was not published");
        assertEquals("RUNNING", world.jobs.find(RollbackBed.JOB).orElseThrow().state());
    }
}
