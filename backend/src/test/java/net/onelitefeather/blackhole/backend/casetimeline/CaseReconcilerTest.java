package net.onelitefeather.blackhole.backend.casetimeline;

import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.casetimeline.SourceRows.AppealRow;
import net.onelitefeather.blackhole.backend.casetimeline.SourceRows.EvidenceRow;
import net.onelitefeather.blackhole.backend.casetimeline.SourceRows.PunishmentRow;
import net.onelitefeather.blackhole.backend.evidence.EvidenceKind;
import net.onelitefeather.blackhole.backend.evidence.EvidenceStatus;
import net.onelitefeather.blackhole.backend.support.CaseTestBed;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseReconcilerTest {

    private static final UUID STAFF = UUID.nameUUIDFromBytes("staff".getBytes());
    private static final UUID INGAME = UUID.nameUUIDFromBytes("ingame".getBytes());
    private static final Duration LOOK_BACK = Duration.ofDays(2);

    /** What the reader finds missing; the reconciler decides what to append from it. */
    static final class GapReader implements CaseSourceReader {

        final List<PunishmentRow> applied = new ArrayList<>();
        final List<PunishmentRow> ended = new ArrayList<>();
        final List<PunishmentRow> expired = new ArrayList<>();
        final List<AppealRow> filed = new ArrayList<>();
        final List<AppealRow> decided = new ArrayList<>();
        final List<EvidenceRow> attached = new ArrayList<>();
        final List<EvidenceRow> statusChanged = new ArrayList<>();
        final List<Long> sinces = new ArrayList<>();

        @Override
        public List<PunishmentRow> appliedMissing(long since, int limit) {
            this.sinces.add(since);
            return this.applied;
        }

        final List<SourceRows.KickRow> kicked = new ArrayList<>();

        @Override
        public List<SourceRows.KickRow> kickedMissing(long since, int limit) {
            return this.kicked;
        }

        final List<SourceRows.WarnRow> warned = new ArrayList<>();
        final List<SourceRows.WarnRow> acknowledged = new ArrayList<>();

        @Override
        public List<SourceRows.WarnRow> warnedMissing(long since, int limit) {
            return this.warned;
        }

        @Override
        public List<SourceRows.WarnRow> acknowledgedMissing(long since, int limit) {
            return this.acknowledged;
        }

        @Override
        public List<PunishmentRow> endedMissing(long since, int limit) {
            return this.ended;
        }

        @Override
        public List<PunishmentRow> expiredMissing(long since, long now, int limit) {
            return this.expired;
        }

        @Override
        public List<AppealRow> filedMissing(long since, int limit) {
            return this.filed;
        }

        @Override
        public List<AppealRow> decidedMissing(long since, int limit) {
            return this.decided;
        }

        @Override
        public List<EvidenceRow> attachedMissing(long since, int limit) {
            return this.attached;
        }

        @Override
        public List<EvidenceRow> statusChangeMissing(long since, int limit) {
            return this.statusChanged;
        }
    }

    private final TestClock clock = new TestClock();
    private final CaseTestBed bed = new CaseTestBed(clock);
    private final GapReader gaps = new GapReader();
    private final CaseReconciler reconciler = new CaseReconciler(gaps, bed.timeline, clock, LOOK_BACK, Duration.ZERO);
    private final CaseReconciler gracious = new CaseReconciler(gaps, bed.timeline, clock, LOOK_BACK, Duration.ofMinutes(5));

    private PunishmentRow punishment(String id, long createdAt, Long updatedAt, Long expiresAt, UUID source, UUID recordedBy) {
        return new PunishmentRow(id, source, recordedBy, null, "network", 0, 1, 40, false, false, createdAt, expiresAt, updatedAt,
                null, null, null, null);
    }

    @Test
    void aLostAppliedEventIsRepairedWithTheTimeAndActorOfThePunishmentRow() {
        long created = clock.millis() - Duration.ofHours(3).toMillis();
        gaps.applied.add(punishment("p-1", created, null, null, STAFF, STAFF));

        int repaired = reconciler.reconcile();

        assertEquals(1, repaired);
        CaseEventRecord event = bed.log.ofType("p-1", "punishment.applied").getFirst();
        assertEquals(CaseEventOrigin.RECONSTRUCTED, event.origin());
        assertEquals(created, event.occurredAt(), "the time of the source row, not the time of the run");
        assertEquals(ActorTrust.VERIFIED, event.actorTrust());
        assertEquals(STAFF, event.actorOid());
        assertEquals("applied:p-1", event.dedupeKey());
    }

    @Test
    void aLostWarnedEventIsRepairedAtTheTimeTheWarningWasWritten() {
        long created = clock.millis() - Duration.ofHours(3).toMillis();
        gaps.warned.add(new SourceRows.WarnRow("w-1", STAFF, STAFF, null, 0, created, null));

        assertEquals(1, reconciler.reconcile());

        CaseEventRecord event = bed.log.ofType("w-1", "punishment.warned").getFirst();
        assertEquals(CaseEventOrigin.RECONSTRUCTED, event.origin());
        assertEquals(created, event.occurredAt(), "the time of the warning row, not the time of the run");
        assertEquals("warned:w-1", event.dedupeKey());
    }

    @Test
    void aLostAcknowledgedEventIsRepairedAtTheTimeThePlayerConfirmedTheWarning() {
        long created = clock.millis() - Duration.ofHours(3).toMillis();
        long acknowledged = clock.millis() - Duration.ofHours(1).toMillis();
        gaps.acknowledged.add(new SourceRows.WarnRow("w-1", STAFF, STAFF, null, 0, created, acknowledged));

        assertEquals(1, reconciler.reconcile());

        CaseEventRecord event = bed.log.ofType("w-1", "punishment.acknowledged").getFirst();
        assertEquals(acknowledged, event.occurredAt(), "when the player confirmed it, not when it was written");
        assertEquals("acknowledged:w-1", event.dedupeKey());
    }

    @Test
    void aLostAppealFilingIsRepairedAtTheTimeTheAppealWasFiled() {
        long filedAt = clock.millis() - Duration.ofHours(2).toMillis();
        UUID appeal = UUID.randomUUID();
        gaps.filed.add(new AppealRow(appeal, "p-1", AppealStatus.ELIGIBLE_PENDING_REVIEW, null, null, filedAt, filedAt));
        clock.advance(Duration.ofMinutes(1));

        reconciler.reconcile();

        CaseEventRecord event = bed.log.ofType("p-1", "appeal.filed").getFirst();
        assertEquals(filedAt, event.occurredAt(), "two hours ago, not now");
        assertEquals(new CaseEventPayload.AppealFiled(appeal.toString(), true), event.payload());
    }

    @Test
    void aLostAppealDecisionIsRepairedWithTheReviewerOfTheRow() {
        long decidedAt = clock.millis() - 5_000;
        UUID appeal = UUID.randomUUID();
        gaps.decided.add(new AppealRow(appeal, "p-1", AppealStatus.DENIED, STAFF, STAFF, 1L, decidedAt));

        reconciler.reconcile();

        CaseEventRecord event = bed.log.ofType("p-1", "appeal.decided").getFirst();
        assertEquals(decidedAt, event.occurredAt());
        assertEquals(STAFF, event.actorOid());
        assertEquals(new CaseEventPayload.AppealDecided(appeal.toString(), "DENIED", null), event.payload());
    }

    @Test
    void aLostEvidenceAttachmentIsRepairedAtTheTimeOfTheAuditLine() {
        UUID evidence = UUID.randomUUID();
        gaps.attached.add(new EvidenceRow(evidence, "p-1", EvidenceKind.LINK, "grim", STAFF, STAFF, null, 100L, 90L,
                EvidenceStatus.VALID, null, null));

        reconciler.reconcile();

        assertEquals(90L, bed.log.ofType("p-1", "evidence.attached").getFirst().occurredAt(), "the attach time, not the creation time");
    }

    @Test
    void aLostEvidenceStatusChangeWithoutATimeIsRepairedAsApproximate() {
        UUID evidence = UUID.randomUUID();
        gaps.statusChanged.add(new EvidenceRow(evidence, "p-1", EvidenceKind.LINK, "grim", STAFF, STAFF, null, 100L, null,
                EvidenceStatus.REVOKED, null, null));

        reconciler.reconcile();

        CaseEventRecord event = bed.log.ofType("p-1", "evidence.revoked").getFirst();
        assertEquals(new CaseEventPayload.EvidenceRevoked(evidence.toString(), true), event.payload(), "the time is a stand-in");
        assertEquals(100L, event.occurredAt());
    }

    @Test
    void aLostRevokeIsRepairedAtTheMetadataUpdateTime() {
        gaps.ended.add(punishment("p-1", 10L, 500L, null, STAFF, STAFF));

        reconciler.reconcile();

        CaseEventRecord event = bed.log.ofType("p-1", "punishment.revoked").getFirst();
        assertEquals(500L, event.occurredAt());
        assertEquals(new CaseEventPayload.Revoked(false), event.payload(), "the recorded update time is exact");
    }

    @Test
    void aLostRevokeWithoutAnUpdateTimeIsApproximate() {
        gaps.ended.add(punishment("p-1", 10L, null, null, STAFF, STAFF));

        reconciler.reconcile();

        assertEquals(new CaseEventPayload.Revoked(true), bed.log.ofType("p-1", "punishment.revoked").getFirst().payload());
    }

    @Test
    void aLostExpiryIsRepairedAtTheExpiryTimeBySystem() {
        gaps.expired.add(punishment("p-1", 10L, null, 700L, STAFF, STAFF));

        reconciler.reconcile();

        CaseEventRecord event = bed.log.ofType("p-1", "punishment.expired").getFirst();
        assertEquals(700L, event.occurredAt());
        assertEquals(ActorTrust.SYSTEM, event.actorTrust());
    }

    @Test
    void anUnlinkedInGameActorStaysUnverifiedWhenRepaired() {
        gaps.applied.add(punishment("p-1", 10L, null, null, INGAME, UUID.randomUUID()));

        reconciler.reconcile();

        assertEquals(ActorTrust.UNVERIFIED, bed.log.ofType("p-1", "punishment.applied").getFirst().actorTrust());
    }

    @Test
    void runningTwiceAddsNothingTheSecondTime() {
        gaps.applied.add(punishment("p-1", 10L, null, null, STAFF, STAFF));
        reconciler.reconcile();

        int second = reconciler.reconcile();

        assertEquals(0, second, "the reader still reports the row, the dedupe key stops the second insert");
        assertEquals(1, bed.log.timeline("p-1").size());
    }

    @Test
    void anEventTheLiveFlowRecordedMeanwhileIsNotDuplicated() {
        PunishmentRow row = punishment("p-1", 10L, null, null, STAFF, STAFF);
        bed.timeline.record(CaseDrafts.applied(row, 1000, 960, CaseEventOrigin.LIVE, 10L));
        gaps.applied.add(row);

        int repaired = reconciler.reconcile();

        assertEquals(0, repaired, "the live event stands");
        assertEquals(CaseEventOrigin.LIVE, bed.log.ofType("p-1", "punishment.applied").getFirst().origin());
    }

    @Test
    void theLookBackWindowIsMeasuredFromTheInjectedClock() {
        reconciler.reconcile();

        assertEquals(clock.millis() - LOOK_BACK.toMillis(), gaps.sinces.getFirst(), "now minus the look-back");
    }

    @Test
    void aFailingLogDoesNotStopThePassAndRepairsNothing() {
        gaps.applied.add(punishment("p-1", 10L, null, null, STAFF, STAFF));
        bed.log.failAppends(new IllegalStateException("database down"));

        int repaired = reconciler.reconcile();

        assertEquals(0, repaired);
        assertTrue(bed.log.all().isEmpty());
        assertEquals(1.0, bed.failures(), "counted, and the next run tries again");
    }

    @Test
    void aSourceRowInsideTheGracePeriodIsSkipped() {
        gaps.applied.add(punishment("p-1", clock.millis() - Duration.ofMinutes(4).toMillis(), null, null, STAFF, STAFF));

        int repaired = gracious.reconcile();

        assertEquals(0, repaired, "its live recording may still be in flight");
        assertTrue(bed.log.all().isEmpty(), "nothing is labelled RECONSTRUCTED yet");
    }

    @Test
    void aSourceRowOlderThanTheGracePeriodIsRepaired() {
        gaps.applied.add(punishment("p-1", clock.millis() - Duration.ofMinutes(6).toMillis(), null, null, STAFF, STAFF));

        int repaired = gracious.reconcile();

        assertEquals(1, repaired);
        assertEquals(CaseEventOrigin.RECONSTRUCTED, bed.log.ofType("p-1", "punishment.applied").getFirst().origin());
    }
}
