package net.onelitefeather.blackhole.backend.rollback;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import net.onelitefeather.blackhole.backend.support.RecordingEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.rollback.RollbackTestSchema.STAFF;
import static net.onelitefeather.blackhole.backend.rollback.RollbackTestSchema.STAFF_REQUESTER;
import static net.onelitefeather.blackhole.backend.rollback.RollbackTestSchema.count;
import static net.onelitefeather.blackhole.backend.rollback.RollbackTestSchema.givenKick;
import static net.onelitefeather.blackhole.backend.rollback.RollbackTestSchema.givenPunishment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A rollback that carries a kick, against a migrated PostgreSQL schema: the preview freezes the kick, the runner
 * marks it and never revokes it, and the job and item rows end in the states the design names. The mark itself is
 * a stand-in that writes the same metadata key with SQL; the production mark is
 * {@code PunishmentApplicationService.markRolledBack}, covered by {@link RollbackMarksTest}.
 * Time comes from a fixed clock; nothing sleeps.
 */
@Tag("integration")
class RollbackMarksIntegrationTest extends PostgresIntegrationTest {

    private static final long NOW = 1_000_000L;
    private static final Duration TTL = Duration.ofHours(1);
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final long FROM = 0L;
    private static final long TO = 2_000_000L;
    private static final long INSIDE = 500_000L;
    private static final String KICK = "kick-1";
    private static final String MUTE = "mute-1";

    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
    private final List<String> revokeAttempts = new ArrayList<>();
    private final List<String> markAttempts = new ArrayList<>();
    private PGSimpleDataSource dataSource;
    private JdbcRollbackJobStore store;

    @BeforeEach
    void givenAMigratedSchema() {
        this.dataSource = RollbackTestSchema.fresh(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        this.store = new JdbcRollbackJobStore(this.dataSource);
    }

    /** Stands in for the production mark: records the job in the kick's metadata, only for a kick. */
    private boolean markInDatabase(String identifier, UUID jobId) {
        this.markAttempts.add(identifier);
        RollbackTestSchema.run(this.dataSource, "UPDATE punishments SET meta_data = meta_data || jsonb_build_object('rollbackJobId', ?::text) "
                + "WHERE identifier = ? AND kind = 'KICK'", jobId.toString(), identifier);
        return count(this.dataSource, "SELECT count(*) FROM punishments WHERE identifier = ? AND meta_data ->> 'rollbackJobId' = ?",
                identifier, jobId.toString()) == 1;
    }

    private RollbackRunner runner() {
        RollbackRunner.ItemRevoke revoke = (owner, section, identifier, attribution, actor, jobId, reason) -> {
            this.revokeAttempts.add(identifier);
            return Optional.empty();
        };
        RollbackRunner.KickMark kicks = (identifier, attribution, actor, jobId) -> markInDatabase(identifier, jobId);
        return new RollbackRunner(this.store, revoke, kicks, new RecordingEventPublisher(), this.clock, "runner-a", LEASE, 50);
    }

    /** A mute the actor made (revocable) and a kick the actor made, both inside the window, previewed into a job. */
    private UUID givenPreviewOfAMuteAndAKick() {
        givenPunishment(this.dataSource, MUTE, 1, "chat", STAFF, STAFF, null, INSIDE, false, true);
        givenKick(this.dataSource, KICK, 2, "network", STAFF, STAFF, INSIDE);
        RollbackDTO.Response preview = assertInstanceOf(RollbackDTO.Response.class,
                new RollbackPreviewService(new JdbcRollbackSelectionReader(this.dataSource), this.store, this.clock, TTL, 10)
                        .preview(new RollbackDTO.PreviewRequest(STAFF, null, FROM, TO), STAFF_REQUESTER));
        return preview.jobId();
    }

    @Test
    void aKickIsMarkedWithTheJobAndNeverRevoked() {
        UUID job = givenPreviewOfAMuteAndAKick();
        assertTrue(this.store.markRunning(job, "compromised account", "key-1", true, NOW), "the preview starts");

        assertTrue(runner().tick(), "the runner claims the job and processes it");

        assertEquals(List.of(MUTE), this.revokeAttempts, "only the punishment goes through the revoke path");
        assertEquals(List.of(KICK), this.markAttempts, "only the kick is marked");
        assertEquals(1, count(this.dataSource, "SELECT count(*) FROM staff_rollback_item WHERE punishment_identifier = ? "
                + "AND state = 'SKIPPED' AND reason_code = 'KICK_MARKED'", KICK), "the kick item records that it was marked");
        assertEquals(1, count(this.dataSource, "SELECT count(*) FROM punishments WHERE identifier = ? AND meta_data ->> 'rollbackJobId' = ?",
                KICK, job.toString()), "the kick row carries the job");
        assertEquals(0, count(this.dataSource, "SELECT count(*) FROM punishments WHERE identifier = ? AND lifted", KICK),
                "a mark does not lift the kick");
        assertEquals(1, count(this.dataSource, "SELECT count(*) FROM staff_rollback_job WHERE id = ? AND state = 'COMPLETED'", job),
                "the job completes once the kick is marked");
    }

    @Test
    void aKickIsSkippedWithoutAMarkWhenTheAdminDidNotAskForMarks() {
        UUID job = givenPreviewOfAMuteAndAKick();
        assertTrue(this.store.markRunning(job, "compromised account", "key-1", false, NOW), "the preview starts");

        runner().tick();

        assertEquals(List.of(), this.markAttempts, "no mark is written when marks were not requested");
        assertEquals(1, count(this.dataSource, "SELECT count(*) FROM staff_rollback_item WHERE punishment_identifier = ? "
                + "AND state = 'SKIPPED' AND reason_code = 'MARKS_NOT_REQUESTED'", KICK));
        assertEquals(0, count(this.dataSource, "SELECT count(*) FROM punishments WHERE identifier = ? AND meta_data ->> 'rollbackJobId' IS NOT NULL", KICK),
                "the kick row is untouched");
    }

    @Test
    void aSecondTickAfterCompletionMarksNothingAgain() {
        UUID job = givenPreviewOfAMuteAndAKick();
        this.store.markRunning(job, "compromised account", "key-1", true, NOW);
        RollbackRunner runner = runner();
        runner.tick();

        assertFalse(runner.tick(), "a completed job is not claimed again");
        assertEquals(List.of(KICK), this.markAttempts, "the kick is marked once");
        assertEquals(Map.of("SKIPPED", 2L), this.store.countByState(job), "the mute was already inactive and the kick was marked");
    }
}
