package net.onelitefeather.blackhole.velocity.guard;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.velocity.component.Permissions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuardHeartbeatTaskTest {

    private static final String PROXY = "velocity-1";

    private final FakeGuardBackend backend = new FakeGuardBackend();
    private final GuardLevelCache levels = new GuardLevelCache();
    private final GuardHeartbeatTask task = new GuardHeartbeatTask(this.backend, this.levels, PROXY);

    @Test
    void theBeatReportsTheProxyIdAndTheStaffCount() {
        this.task.beat(List.of(StaffPlayers.with(UUID.randomUUID(), Permissions.STAFF)));

        assertEquals(List.of(PROXY + ":1"), this.backend.reports, "the beat carries the proxy id and one staff member");
    }

    @Test
    void theEffectiveLevelFromTheAnswerBecomesTheCachedLevel() {
        this.backend.effectiveLevel = "STRICT";

        this.task.beat(List.of());

        assertEquals(GuardLevel.STRICT, this.levels.current(), "the level in the heartbeat answer is the one the proxy uses");
    }

    @Test
    void aFailedBeatKeepsTheCachedLevelAndCountsTheFailure() {
        this.levels.update(GuardLevel.LOCKDOWN);
        this.backend.failure = new ApiException(503, "unavailable");

        this.task.beat(List.of());

        assertEquals(GuardLevel.LOCKDOWN, this.levels.current(), "a failed heartbeat must not change the level");
        assertEquals(1, this.task.failures(), "the failed beat is counted");
    }
}
