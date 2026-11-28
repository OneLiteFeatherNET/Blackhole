package net.onelitefeather.blackhole.backend.rollback;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.rollback.RollbackTestSchema.STAFF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The execution SQL of {@link JdbcRollbackJobStore} against a migrated PostgreSQL schema: the lease that
 * lets only one runner work a job, its takeover after expiry, and the item states up to completion.
 * Time comes from an injected {@link TestClock}; nothing sleeps.
 */
@Tag("integration")
class RollbackLeaseIntegrationTest extends PostgresIntegrationTest {

    private static final UUID JOB = UUID.nameUUIDFromBytes("lease-job".getBytes());
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final String OWNER = "ab".repeat(64);

    private final TestClock clock = new TestClock();
    private JdbcRollbackJobStore store;

    @BeforeEach
    void givenARunningJobWithThreeItems() {
        PGSimpleDataSource dataSource = RollbackTestSchema.fresh(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        this.store = new JdbcRollbackJobStore(dataSource);
        long now = this.clock.millis();
        this.store.insertPreview(new RollbackJobStore.PreviewJob(JOB, STAFF, RollbackSelector.staffActor(STAFF), 0L, now,
                now, now + Duration.ofHours(1).toMillis()), List.of(
                new RollbackJobStore.FrozenItem("item-a", OWNER, "chat", "PUNISHMENT"),
                new RollbackJobStore.FrozenItem("item-b", OWNER, "chat", "PUNISHMENT"),
                new RollbackJobStore.FrozenItem("item-c", OWNER, "chat", "PUNISHMENT")));
        assertTrue(this.store.markRunning(JOB, "compromised account", "key-1", false, now), "the preview starts once");
    }

    private Optional<UUID> claim(String holder) {
        long now = this.clock.millis();
        return this.store.claimRunningJob(holder, now, now + LEASE.toMillis());
    }

    @Test
    void twoRunnersCompeteForTheSameJobAndOnlyOneGetsTheLease() {
        assertEquals(Optional.of(JOB), claim("runner-a"), "the first runner takes the lease");

        assertFalse(claim("runner-b").isPresent(), "the second runner finds the job leased");
    }

    @Test
    void anExpiredLeaseCanBeTakenOver() {
        claim("runner-a");
        this.clock.advance(LEASE.plusMinutes(1));

        assertEquals(Optional.of(JOB), claim("runner-b"), "after the lease expired another runner takes the job over");
    }

    @Test
    void aReleasedLeaseIsFreeAtOnce() {
        claim("runner-a");
        this.store.releaseLease(JOB, "runner-a");

        assertEquals(Optional.of(JOB), claim("runner-b"), "a runner that releases its lease does not make others wait");
    }

    @Test
    void aSecondStartOfTheSameJobIsRefused() {
        long now = this.clock.millis();

        assertFalse(this.store.markRunning(JOB, "compromised account", "key-2", false, now), "only one start wins");
    }

    @Test
    void itemsMoveToTheirFinalStatesAndTheJobCompletes() {
        long now = this.clock.millis();
        List<RollbackJobStore.Item> pending = this.store.nextItems(JOB, 10);
        assertEquals(3, pending.size(), "all three items are pending");

        this.store.markItem(JOB, "item-a", "REVOKED", null, now);
        this.store.markItem(JOB, "item-b", "SKIPPED", "ALREADY_INACTIVE", now);
        this.store.markItem(JOB, "item-c", "FAILED", "REVOKE_FAILED", now);

        assertEquals(List.of(), this.store.nextItems(JOB, 10), "no pending item is left, and FAILED is not handed out again");
        assertEquals(0L, this.store.countOpen(JOB));
        assertEquals(Map.of("REVOKED", 1L, "SKIPPED", 1L, "FAILED", 1L), this.store.countByState(JOB));
        assertTrue(this.store.markCompleted(JOB, now), "the job completes once nothing is pending");
        assertEquals("COMPLETED", this.store.find(JOB).orElseThrow().state());
        assertFalse(this.store.markCompleted(JOB, now), "a completed job is not completed again");
    }
}
