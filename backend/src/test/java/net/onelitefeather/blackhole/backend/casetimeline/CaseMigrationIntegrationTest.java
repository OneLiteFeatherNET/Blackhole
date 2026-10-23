package net.onelitefeather.blackhole.backend.casetimeline;

import net.onelitefeather.blackhole.backend.PostgresIntegrationTest;
import net.onelitefeather.blackhole.backend.elo.service.EloService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs V12 over a database that holds punishments, appeals and evidence from before the case
 * existed. The rows are fixed, and so are their times; only the expiry check reads the database
 * clock, against expiry times far in the past and the future.
 */
@Tag("integration")
class CaseMigrationIntegrationTest extends PostgresIntegrationTest {

    private static final String OWNER = "ab".repeat(64);
    private static final UUID STAFF = UUID.nameUUIDFromBytes("staff".getBytes());
    private static final UUID LEGACY_STAFF = UUID.nameUUIDFromBytes("legacy-staff".getBytes());
    private static final UUID LEGACY_REVIEWER = UUID.nameUUIDFromBytes("legacy-reviewer".getBytes());
    private static final UUID REVIEWER = UUID.nameUUIDFromBytes("reviewer".getBytes());
    private static final long T0 = 1_700_000_000_000L;
    private static final long LONG_AGO = 1_000L;
    private static final long FAR_FUTURE = 4_102_444_800_000L;

    private static final String ACTIVE_BAN = "active-ban";
    private static final String LEGACY_DENIED = "legacy-denied";
    private static final String EXPIRED_MUTE = "expired-mute";
    private static final String LIFTED_BY_APPEAL = "lifted-by-appeal";
    private static final String REVOKED = "revoked-by-staff";
    private static final String ESCALATION = "escalation-ban";
    private static final String LEGACY_REVOKED = "legacy-revoked";

    private static final UUID DENIED_APPEAL = UUID.nameUUIDFromBytes("denied-appeal".getBytes());
    private static final UUID LIFTING_APPEAL = UUID.nameUUIDFromBytes("lifting-appeal".getBytes());
    private static final UUID ATTACHED_EVIDENCE = UUID.nameUUIDFromBytes("attached-evidence".getBytes());
    private static final UUID LIFTED_EVIDENCE = UUID.nameUUIDFromBytes("lifted-evidence".getBytes());

    private CaseDatabase db;

    @BeforeEach
    void givenADatabaseFromBeforeTheCase() {
        db = new CaseDatabase(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).migrate("11");

        punishment(ACTIVE_BAN, STAFF, STAFF, null, false, false, T0, 1, 3, 40,
                "{\"creation_date\":%d,\"expiration_date\":%d}".formatted(T0, FAR_FUTURE));
        punishment(LEGACY_DENIED, LEGACY_STAFF, null, null, false, false, T0 + 10, null, null, null,
                "{\"expiration_date\":%d}".formatted(FAR_FUTURE));
        punishment(EXPIRED_MUTE, STAFF, STAFF, null, false, false, T0 + 20, 0, 1, 0,
                "{\"expiration_date\":%d}".formatted(LONG_AGO + 500));
        punishment(LIFTED_BY_APPEAL, STAFF, STAFF, null, false, true, T0 + 30, 0, 1, 80,
                "{\"expiration_date\":%d,\"update_date\":%d,\"revokedBy\":\"%s\",\"recordedByOid\":\"%s\"}"
                        .formatted(FAR_FUTURE, T0 + 5_000, REVIEWER, REVIEWER));
        punishment(REVOKED, STAFF, STAFF, null, false, true, T0 + 40, 0, 1, 0,
                "{\"expiration_date\":%d,\"update_date\":%d,\"revokedBy\":\"%s\",\"recordedByOid\":\"%s\"}"
                        .formatted(FAR_FUTURE, T0 + 6_000, STAFF, STAFF));
        punishment(ESCALATION, EloService.SYSTEM_ELO_SOURCE, null, null, true, false, T0 + 50, null, null, null, "{}");
        punishment(LEGACY_REVOKED, LEGACY_STAFF, null, null, false, true, T0 + 60, null, null, null,
                "{\"expiration_date\":%d}".formatted(FAR_FUTURE));

        appeal(DENIED_APPEAL, LEGACY_DENIED, 5, LEGACY_REVIEWER, null, T0 + 100, T0 + 200);
        appeal(LIFTING_APPEAL, LIFTED_BY_APPEAL, 3, REVIEWER, REVIEWER, T0 + 300, T0 + 5_000);

        evidence(ATTACHED_EVIDENCE, ACTIVE_BAN, 0, null);
        db.execute("INSERT INTO evidence_audit (identifier, evidence_identifier, punishment_identifier, owner, action, occurred_at) "
                + "VALUES (?, ?, ?, ?, 0, ?)", UUID.randomUUID(), ATTACHED_EVIDENCE, ACTIVE_BAN, OWNER, T0 + 7);
        evidence(LIFTED_EVIDENCE, LIFTED_BY_APPEAL, 2, null);
        db.execute("INSERT INTO evidence_items (identifier, owner, kind, source_system, created_at, status) VALUES (?, ?, 3, 'pending', ?, 0)",
                UUID.randomUUID(), OWNER, T0);

        db.migrate("latest");
    }

    private void punishment(String id, UUID source, UUID recordedBy, UUID actor, boolean permanent, boolean lifted, long createdAt,
                            Integer stepIndex, Integer stepCount, Integer eloCost, String metaData) {
        db.execute("INSERT INTO punishments (identifier, source, recorded_by_oid, actor_oid, owner, section_key, permanent, lifted, "
                        + "created_at, short_id, step_index, step_count, elo_cost, meta_data) VALUES (?, ?, ?, ?, ?, 'network', ?, ?, ?, 'ABCDEF01', ?, ?, ?, ?::jsonb)",
                id, source, recordedBy, actor, OWNER, permanent, lifted, createdAt, stepIndex, stepCount, eloCost, metaData);
    }

    private void appeal(UUID id, String punishment, int status, UUID decidedBy, UUID recordedBy, long createdAt, long updatedAt) {
        db.execute("INSERT INTO appeals (identifier, appellant_hash, created_at, decided_by, status, updated_at, punishment_identifier, recorded_by_oid) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", id, OWNER, createdAt, decidedBy, status, updatedAt, punishment, recordedBy);
    }

    private void evidence(UUID id, String punishment, int status, Long statusChangedAt) {
        db.execute("INSERT INTO evidence_items (identifier, punishment_identifier, owner, kind, source_system, created_at, created_by, "
                        + "recorded_by_oid, status, status_changed_at, lifted_by_appeal) VALUES (?, ?, ?, 3, 'grim', ?, ?, ?, ?, ?, ?)",
                id, punishment, OWNER, T0 + 50, STAFF, STAFF, status, statusChangedAt, status == 2 ? LIFTING_APPEAL : null);
    }

    private List<CaseEventRecord> timeline(String punishment) {
        return new JdbcCaseEventReader(db.dataSource).timeline(punishment);
    }

    private CaseEventRecord only(String punishment, String type) {
        List<CaseEventRecord> found = timeline(punishment).stream().filter(event -> event.type().equals(type)).toList();
        assertEquals(1, found.size(), "exactly one " + type + " for " + punishment + " but found " + found);
        return found.getFirst();
    }

    private String status(String punishment) {
        return db.text("SELECT status FROM punishment_case WHERE punishment_identifier = ?", punishment);
    }

    @Test
    void everyExistingPunishmentGetsACase() {
        assertEquals(7, db.count("SELECT count(*) FROM punishment_case"), "one case per punishment");
        assertEquals(7, db.count("SELECT count(*) FROM punishments"));
    }

    @Test
    void casesOfEndedPunishmentsStartClosedAndTheOthersOpen() {
        assertEquals("OPEN", status(ACTIVE_BAN), "still running");
        assertEquals("OPEN", status(LEGACY_DENIED), "still running");
        assertEquals("OPEN", status(ESCALATION), "permanent, never ends by itself");
        assertEquals("CLOSED", status(EXPIRED_MUTE), "expired");
        assertEquals("CLOSED", status(LIFTED_BY_APPEAL), "lifted");
        assertEquals("CLOSED", status(REVOKED), "revoked");
        assertEquals("CLOSED", status(LEGACY_REVOKED), "lifted");
    }

    @Test
    void backfilledCasesHaveNeutralMetadata() {
        assertEquals(7, db.count("SELECT count(*) FROM punishment_case WHERE priority = 'NORMAL' AND assignee_oid IS NULL "
                + "AND tags = '[]'::jsonb AND version = 0"), "default priority, no assignee, no tags");
    }

    @Test
    void everyBackfilledEventIsMarkedBackfilledAndStaffOnly() {
        assertEquals(0, db.count("SELECT count(*) FROM case_event WHERE origin <> 'BACKFILLED' OR visibility <> 'STAFF'"),
                "nothing in the backfill is live, and nothing is visible to players");
        assertTrue(db.count("SELECT count(*) FROM case_event") > 0);
    }

    @Test
    void everyPunishmentHasItsAppliedEventAtItsCreationTimeWithItsStep() {
        CaseEventRecord applied = only(ACTIVE_BAN, "punishment.applied");

        assertEquals(T0, applied.occurredAt());
        assertEquals(new CaseEventPayload.Applied("network", 1, 3, 40, null, null, false, false, null, FAR_FUTURE), applied.payload());
        assertEquals("applied:" + ACTIVE_BAN, applied.dedupeKey());
    }

    @Test
    void aPunishmentFromBeforeSsoIsAttributedToItsSourceAsUnverified() {
        CaseEventRecord applied = only(LEGACY_DENIED, "punishment.applied");

        assertEquals(ActorTrust.UNVERIFIED, applied.actorTrust(), "a legacy actor never looks verified");
        assertEquals(LEGACY_STAFF, applied.actorSource());
        assertNull(applied.actorOid());
    }

    @Test
    void aPunishmentWithARecordedPrincipalIsVerified() {
        CaseEventRecord applied = only(ACTIVE_BAN, "punishment.applied");

        assertEquals(ActorTrust.VERIFIED, applied.actorTrust());
        assertEquals(STAFF, applied.actorOid());
        assertNull(applied.actorSource());
    }

    @Test
    void anEscalationPunishmentIsBackfilledAsTheSystemAndEscalated() {
        CaseEventRecord applied = only(ESCALATION, "punishment.applied");

        assertEquals(ActorTrust.SYSTEM, applied.actorTrust());
        CaseEventPayload.Applied payload = assertInstanceOf(CaseEventPayload.Applied.class, applied.payload());
        assertTrue(payload.escalated() && payload.permanent());
    }

    @Test
    void aDecidedAppealOfALegacyPunishmentIsBackfilledAsFiledAndDecidedAndUnverified() {
        assertEquals(List.of("punishment.applied", "appeal.filed", "appeal.decided"),
                timeline(LEGACY_DENIED).stream().map(CaseEventRecord::type).toList(), "in timeline order");
        CaseEventRecord decided = only(LEGACY_DENIED, "appeal.decided");
        assertEquals(T0 + 200, decided.occurredAt(), "the appeal's update time");
        assertEquals(ActorTrust.UNVERIFIED, decided.actorTrust());
        assertEquals(LEGACY_REVIEWER, decided.actorSource(), "the decided_by of the legacy row");
        assertEquals(new CaseEventPayload.AppealDecided(DENIED_APPEAL.toString(), "DENIED", null), decided.payload());
        assertEquals(new CaseEventPayload.AppealFiled(DENIED_APPEAL.toString(), true), only(LEGACY_DENIED, "appeal.filed").payload());
    }

    @Test
    void aDecidedAppealWithARecordedReviewerIsVerified() {
        CaseEventRecord decided = only(LIFTED_BY_APPEAL, "appeal.decided");

        assertEquals(ActorTrust.VERIFIED, decided.actorTrust());
        assertEquals(REVIEWER, decided.actorOid());
    }

    @Test
    void anExpiredPunishmentGetsAnExpiredEventAtItsExpiryTimeBySystem() {
        CaseEventRecord expired = only(EXPIRED_MUTE, "punishment.expired");

        assertEquals(LONG_AGO + 500, expired.occurredAt());
        assertEquals(ActorTrust.SYSTEM, expired.actorTrust());
        assertEquals(new CaseEventPayload.Expired(LONG_AGO + 500), expired.payload());
    }

    @Test
    void aRunningPunishmentGetsNoExpiredEvent() {
        assertTrue(timeline(ACTIVE_BAN).stream().noneMatch(event -> event.type().equals("punishment.expired")));
    }

    @Test
    void aPunishmentLiftedByAGrantedAppealGetsALiftedEventAtTheRecordedTime() {
        CaseEventRecord lifted = only(LIFTED_BY_APPEAL, "punishment.lifted");

        assertEquals(T0 + 5_000, lifted.occurredAt(), "the update time the lift wrote");
        assertEquals(new CaseEventPayload.Lifted(LIFTING_APPEAL.toString(), false), lifted.payload());
        assertEquals(REVIEWER, lifted.actorOid());
        assertEquals(ActorTrust.VERIFIED, lifted.actorTrust());
    }

    @Test
    void aRevokedPunishmentGetsARevokedEventByTheRecordedStaffMember() {
        CaseEventRecord revoked = only(REVOKED, "punishment.revoked");

        assertEquals(T0 + 6_000, revoked.occurredAt());
        assertEquals(new CaseEventPayload.Revoked(false), revoked.payload());
        assertEquals(STAFF, revoked.actorOid());
    }

    @Test
    void aLegacyRevokeWithoutATimeIsApproximateAndItsActorUnknown() {
        CaseEventRecord revoked = only(LEGACY_REVOKED, "punishment.revoked");

        assertEquals(T0 + 60, revoked.occurredAt(), "the creation time stands in");
        assertEquals(new CaseEventPayload.Revoked(true), revoked.payload(), "flagged approximate");
        assertEquals(ActorTrust.UNVERIFIED, revoked.actorTrust());
    }

    @Test
    void evidenceIsBackfilledAtTheAuditTimeAndPendingEvidenceIsLeftOut() {
        CaseEventRecord attached = only(ACTIVE_BAN, "evidence.attached");

        assertEquals(T0 + 7, attached.occurredAt(), "the ATTACHED audit line has the exact time");
        assertEquals(new CaseEventPayload.EvidenceAttached(ATTACHED_EVIDENCE.toString(), "LINK", "grim"), attached.payload());
        assertEquals(2, db.count("SELECT count(*) FROM case_event WHERE type = 'evidence.attached'"),
                "two attached items; the pending one belongs to no punishment");
    }

    @Test
    void liftedEvidenceWithoutAChangeTimeIsApproximate() {
        CaseEventRecord lifted = only(LIFTED_BY_APPEAL, "evidence.lifted");

        assertEquals(new CaseEventPayload.EvidenceLifted(LIFTED_EVIDENCE.toString(), LIFTING_APPEAL.toString(), true), lifted.payload());
    }

    @Test
    void everyBackfilledEventReadsAsATypedPayloadOfThisRelease() {
        List<CaseEventRecord> all = List.of(ACTIVE_BAN, LEGACY_DENIED, EXPIRED_MUTE, LIFTED_BY_APPEAL, REVOKED, ESCALATION, LEGACY_REVOKED)
                .stream().flatMap(id -> timeline(id).stream()).toList();

        assertFalse(all.isEmpty());
        for (CaseEventRecord event : all) {
            assertFalse(event.payload() instanceof CaseEventPayload.Unknown,
                    "the SQL backfill and the Java payloads must agree: " + event.type() + " " + event.body());
        }
    }

    @Test
    void runningTheBackfillAgainAddsNothing() throws IOException {
        long events = db.count("SELECT count(*) FROM case_event");
        long cases = db.count("SELECT count(*) FROM punishment_case");

        db.script(backfillOfTheMigration());

        assertEquals(events, db.count("SELECT count(*) FROM case_event"), "the dedupe keys stop a second insert");
        assertEquals(cases, db.count("SELECT count(*) FROM punishment_case"));
    }

    @Test
    void deletingAPunishmentDeletesItsCaseAndEvents() {
        db.execute("DELETE FROM punishments WHERE identifier = ?", EXPIRED_MUTE);

        assertEquals(0, db.count("SELECT count(*) FROM punishment_case WHERE punishment_identifier = ?", EXPIRED_MUTE));
        assertEquals(0, db.count("SELECT count(*) FROM case_event WHERE punishment_identifier = ?", EXPIRED_MUTE));
        assertEquals(6, db.count("SELECT count(*) FROM punishment_case"), "the other cases stay");
    }

    @Test
    void theLogHasNoActorNameColumn() {
        assertEquals(0, db.count("SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() "
                + "AND table_name = 'case_event' AND column_name ILIKE '%name%'"), "names are joined at read time, never stored");
    }

    /** The idempotent part of V12: everything from its BACKFILL marker on. */
    private static String backfillOfTheMigration() throws IOException {
        try (InputStream in = CaseMigrationIntegrationTest.class.getResourceAsStream("/db/migration/V12__punishment_case.sql")) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return sql.substring(sql.indexOf("-- BACKFILL:"));
        }
    }
}
