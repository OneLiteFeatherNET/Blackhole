package net.onelitefeather.blackhole.backend.rollback;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The rollback routes over HTTP against the real database: the admin role on every route, the start and repeat
 * rules, the progress and cancel answers, and one full path from preview to cancel. Each test uses its own random
 * oids and owners, so the tests share nothing in the class-wide database. The scheduled runner is pushed far out, so
 * a started job stays RUNNING while the test reads it.
 */
@Tag("integration")
class RollbackControllerTest extends ApiIntegrationTest {

    private static final String PREVIEW = "/admin/rollback/preview";

    @Override
    protected Map<String, Object> extraProperties() {
        Map<String, Object> properties = new HashMap<>(super.extraProperties());
        properties.put("blackhole.rollback.tick-interval", "PT1H");
        return properties;
    }

    private PGSimpleDataSource database() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    private static String owner() {
        return (UUID.randomUUID().toString() + UUID.randomUUID() + UUID.randomUUID() + UUID.randomUUID()).replace("-", "");
    }

    /** A HUMAN staff actor with one active chat mute written by that actor. */
    private UUID givenStaffWithAnActiveMute(long createdAt) {
        PGSimpleDataSource db = database();
        UUID staff = UUID.randomUUID();
        String owner = owner();
        String identifier = UUID.randomUUID().toString();
        RollbackTestSchema.run(db, "INSERT INTO staff_actor (oid, kind, display_name, first_seen_at, last_seen_at) VALUES (?, 'HUMAN', 'Staff', 1, 1)",
                staff);
        RollbackTestSchema.run(db, "INSERT INTO punishment_profiles (owner, meta_data) VALUES (?, '{}'::jsonb)", owner);
        RollbackTestSchema.run(db, "INSERT INTO punishments (identifier, source, recorded_by_oid, actor_oid, owner, section_key, permanent, lifted, "
                + "created_at, short_id, meta_data) VALUES (?, ?, ?, NULL, ?, 'chat', false, false, ?, 'ABCDEF01', "
                + "'{\"reason\":\"because\"}'::jsonb)", identifier, staff, staff, owner, createdAt);
        RollbackTestSchema.run(db, "INSERT INTO active_punishments (owner, section_key, punishment_identifier) VALUES (?, 'chat', ?)",
                owner, identifier);
        return staff;
    }

    private Map<String, Object> previewBody(UUID staff) {
        return Map.of("staffOid", staff.toString(), "windowFrom", 0L, "windowTo", 10_000L);
    }

    @Test
    void aModeratorIsForbiddenOnEveryRollbackRoute() {
        String moderator = this.jwt.moderator();
        String job = UUID.randomUUID().toString();

        assertEquals(403, post(PREVIEW, previewBody(UUID.randomUUID()), moderator).status(), "preview");
        assertEquals(403, post("/admin/rollback/" + job + "/execute", Map.of("reason", "r", "suspendLink", false, "includeMarks", false), moderator,
                Map.of("Idempotency-Key", "key-1")).status(), "execute");
        assertEquals(403, get("/admin/rollback/" + job, moderator).status(), "progress");
        assertEquals(403, post("/admin/rollback/" + job + "/cancel", null, moderator).status(), "cancel");
    }

    @Test
    void aServiceAccountIsRefusedAsPreviewSelector() {
        Reply reply = post(PREVIEW, previewBody(UUID.randomUUID()), this.jwt.admin());

        assertEquals(400, reply.status(), reply.body());
        assertEquals("ROLLBACK_SELECTOR_INVALID", reply.json().get("kind"));
    }

    @Test
    void executeWithoutAnIdempotencyKeyIsRefused() {
        Reply reply = post("/admin/rollback/" + UUID.randomUUID() + "/execute", Map.of("reason", "compromised account",
                "suspendLink", false, "includeMarks", false), this.jwt.admin());

        assertEquals(400, reply.status(), reply.body());
    }

    @Test
    void progressOfAnUnknownRollbackIsNotFound() {
        assertEquals(404, get("/admin/rollback/" + UUID.randomUUID(), this.jwt.admin()).status());
    }

    @Test
    void cancelOfAnUnknownRollbackIsNotFound() {
        assertEquals(404, post("/admin/rollback/" + UUID.randomUUID() + "/cancel", null, this.jwt.admin()).status());
    }

    @Test
    void aPreviewIsStartedOnceForTheSameKeyAndCancelledOnce() {
        UUID staff = givenStaffWithAnActiveMute(1_000L);
        String admin = this.jwt.admin();

        Reply preview = post(PREVIEW, previewBody(staff), admin);
        assertEquals(201, preview.status(), preview.body());
        assertEquals(1, preview.json().get("revocable"), "the one active mute is frozen");
        String jobPath = "/admin/rollback/" + preview.json().get("jobId");

        Map<String, Object> execute = Map.of("reason", "compromised account", "suspendLink", false, "includeMarks", false);
        Reply started = post(jobPath + "/execute", execute, admin, Map.of("Idempotency-Key", "key-1"));
        assertEquals(202, started.status(), started.body());
        assertEquals("RUNNING", started.json().get("state"));
        assertEquals(false, started.json().get("repeated"));

        Reply repeat = post(jobPath + "/execute", execute, admin, Map.of("Idempotency-Key", "key-1"));
        assertEquals(202, repeat.status(), repeat.body());
        assertEquals(true, repeat.json().get("repeated"), "the same key answers the same job");

        Reply progress = get(jobPath, admin);
        assertEquals(200, progress.status(), progress.body());
        assertEquals("RUNNING", progress.json().get("state"));

        Reply cancelled = post(jobPath + "/cancel", null, admin);
        assertEquals(200, cancelled.status(), cancelled.body());
        assertEquals("CANCELLED", cancelled.json().get("state"));

        assertEquals(409, post(jobPath + "/cancel", null, admin).status(), "a cancelled rollback cannot be cancelled again");
    }
}
