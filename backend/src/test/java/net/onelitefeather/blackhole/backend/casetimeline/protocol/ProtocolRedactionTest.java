package net.onelitefeather.blackhole.backend.casetimeline.protocol;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.casetimeline.ActorTrust;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventPayload;
import net.onelitefeather.blackhole.backend.casetimeline.CaseReader;
import net.onelitefeather.blackhole.backend.casetimeline.MessageAuthorKind;
import net.onelitefeather.blackhole.backend.casetimeline.MessageChannel;
import net.onelitefeather.blackhole.backend.evidence.EvidenceItemEntity;
import net.onelitefeather.blackhole.backend.evidence.EvidenceKind;
import net.onelitefeather.blackhole.backend.evidence.EvidenceTestBed;
import net.onelitefeather.blackhole.backend.evidence.service.GeneratedEvidenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static net.onelitefeather.blackhole.backend.casetimeline.protocol.ProtocolFixtures.APPEAL;
import static net.onelitefeather.blackhole.backend.casetimeline.protocol.ProtocolFixtures.PUNISHMENT;
import static net.onelitefeather.blackhole.backend.casetimeline.protocol.ProtocolFixtures.STAFF_OID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Anonymising a staff member removes the name snapshot from the chat and regenerates the protocol without it; the version
 * that showed the name is no longer served. In-memory stores and the real evidence service over an in-memory blob store.
 */
class ProtocolRedactionTest {

    private static final String SNAPSHOT = "Mia K.";

    @TempDir
    Path spool;

    private EvidenceTestBed bed;
    private FakeProtocolSource source;
    private InMemoryProtocolStore store;
    private AppealProtocolService service;

    @BeforeEach
    void givenADecidedAppealWhoseStaffMessageWasWrittenUnderAName() {
        bed = new EvidenceTestBed(spool);
        bed.givenPunishment(PUNISHMENT);
        source = new FakeProtocolSource();
        store = new InMemoryProtocolStore();
        GeneratedEvidenceService gateway = new GeneratedEvidenceService(bed.items.repository(), bed.audit.repository(),
                bed.punishments.repository(), bed.blobs, bed.events, bed.clock, 16 * 1024 * 1024);
        service = new AppealProtocolService(source, store, gateway, bed.cases.timeline, bed.events, bed.clock, new SimpleMeterRegistry(),
                Duration.ZERO, 3);
        long end = bed.clock.millis();
        source.appeals.add(ProtocolFixtures.appeal(APPEAL, AppealStatus.DENIED, end - 3_000, end));
        source.entries.add(ProtocolFixtures.filed(1, end - 3_000, APPEAL));
        source.entries.add(namedStaffMessage(2, end - 2_000, SNAPSHOT));
        source.entries.add(ProtocolFixtures.decided(3, end, APPEAL, "DENIED"));
    }

    /** A staff message as the write path stores it: the snapshot in the author object, payload version 2. */
    private static CaseReader.Entry namedStaffMessage(long seq, long at, String snapshot) {
        CaseEventPayload.MessageAdded added = new CaseEventPayload.MessageAdded("m-" + seq, APPEAL.toString(), MessageAuthorKind.STAFF, null,
                MessageChannel.PANEL, "We looked at the logs.", List.of(), List.of(), snapshot);
        return ProtocolFixtures.entry(ProtocolFixtures.event(seq, at, "message.added", ActorTrust.VERIFIED, "m-" + seq, "PLAYER", added.toMap()),
                null);
    }

    /** The row as the anonymisation SQL leaves it: no snapshot, the same identifiers and the same order. */
    private static CaseReader.Entry withoutSnapshot(CaseReader.Entry entry) {
        CaseEventPayload.MessageAdded added = (CaseEventPayload.MessageAdded) entry.event().payload();
        CaseEventPayload.MessageAdded cleared = new CaseEventPayload.MessageAdded(added.messageId(), added.appealId(), added.authorKind(),
                added.participantId(), added.channel(), added.text(), added.attachments(), added.embeds(), null);
        return ProtocolFixtures.entry(ProtocolFixtures.event(entry.event().seq(), entry.event().occurredAt(), "message.added",
                ActorTrust.VERIFIED, added.messageId(), "PLAYER", cleared.toMap()), null);
    }

    private List<EvidenceItemEntity> transcripts() {
        return bed.items.all().stream().filter(item -> item.getKind() == EvidenceKind.TRANSCRIPT).toList();
    }

    private String textOf(EvidenceItemEntity item) {
        return new String(bed.blobs.bytes(item.getFileKey()), StandardCharsets.UTF_8);
    }

    @Test
    void theProtocolShowsTheSnapshotNameBeforeTheAnonymisation() {
        AppealProtocolGenerator.Result result = service.generate(APPEAL, true);

        assertEquals(AppealProtocolGenerator.Outcome.STORED, result.outcome());
        assertTrue(textOf(transcripts().getFirst()).contains(SNAPSHOT), "the precondition: the name is in version 1");
    }

    @Test
    void anonymisationRegeneratesTheProtocolWithoutTheSnapshotName() {
        service.generate(APPEAL, true);
        List<CaseReader.Entry> cleared = new ArrayList<>();
        for (CaseReader.Entry entry : source.entries) {
            cleared.add(entry.event().seq() == 2 ? withoutSnapshot(entry) : entry);
        }
        source.entries.clear();
        source.entries.addAll(cleared);

        service.afterStaffAnonymized(STAFF_OID);

        ProtocolVersion active = store.active(APPEAL).orElseThrow();
        assertEquals(2, active.version(), "a new version replaces the one that named the staff member");
        assertFalse(textOf(transcripts().getLast()).contains(SNAPSHOT), "the regenerated protocol never shows the name");
    }

    @Test
    void theVersionThatNamedTheStaffMemberIsNoLongerReadable() {
        service.generate(APPEAL, true);
        List<CaseReader.Entry> cleared = new ArrayList<>();
        for (CaseReader.Entry entry : source.entries) {
            cleared.add(entry.event().seq() == 2 ? withoutSnapshot(entry) : entry);
        }
        source.entries.clear();
        source.entries.addAll(cleared);

        service.afterStaffAnonymized(STAFF_OID);

        ProtocolVersion first = store.versionsOf(PUNISHMENT).stream().filter(version -> version.version() == 1).findFirst().orElseThrow();
        assertFalse(first.status().readable(), "the superseded version, which shows the name, is not served");
    }
}
