package net.onelitefeather.blackhole.backend.inbox;

import net.onelitefeather.blackhole.backend.casetimeline.ActorTrust;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventPayload;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The changedByOthers section of the inbox over an in-memory source: which latest events count, the window, the
 * kind, the change value, the anonymized name, the order and the cap. Nothing here touches a database or the system clock.
 */
class ChangedByOthersServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration WINDOW = Duration.ofHours(24);
    private static final UUID CALLER = UUID.fromString("c0000000-0000-4000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("c0000000-0000-4000-8000-000000000002");

    /** A source that answers the latest events it was given. The window is applied by the service as well. */
    private static final class FakeSource implements InboxSource {
        private final List<LatestChange> changes = new ArrayList<>();

        FakeSource with(LatestChange change) {
            this.changes.add(change);
            return this;
        }

        @Override
        public List<InboxDTO.Item> oldest(InboxSection section, int limit) {
            return List.of();
        }

        @Override
        public long count(InboxSection section) {
            return 0;
        }

        @Override
        public List<LatestChange> latestChangesSince(long since) {
            return this.changes.stream().filter(change -> change.occurredAt() >= since).toList();
        }
    }

    private static LatestChange change(String punishment, String type, ActorTrust trust, UUID actor, String name, Instant at) {
        return new LatestChange(punishment, "A1B2C3D4", type, null, at.toEpochMilli(), trust, actor, name);
    }

    private static LatestChange appealChange(String punishment, String type, String appealId, UUID actor, Instant at) {
        return new LatestChange(punishment, "E5F6A7B8", type, appealId, at.toEpochMilli(), ActorTrust.VERIFIED, actor, "Mia K.");
    }

    private static InboxDTO.ChangedSection section(FakeSource source) {
        InboxDTO result = new InboxService(source, CLOCK, WINDOW).summary(CALLER);
        return assertInstanceOf(InboxDTO.Response.class, result, "a readable source answers the response variant").changedByOthers();
    }

    @Test
    void aLatestChangeByAnotherVerifiedStaffMemberIsListedWithItsChangeAndName() {
        FakeSource source = new FakeSource().with(change("p-1", CaseEventPayload.STATUS_CHANGED, ActorTrust.VERIFIED, OTHER, "Mia K.",
                NOW.minusSeconds(7200)));

        InboxDTO.ChangedSection section = section(source);

        assertEquals(1, section.count());
        InboxDTO.ChangedItem item = section.items().getFirst();
        assertEquals(InboxChangeKind.CASE, item.kind());
        assertEquals("A1B2C3D4", item.id());
        assertEquals("A1B2C3D4", item.reference());
        assertEquals(InboxChange.STATUS, item.change());
        assertEquals("Mia K.", item.byName());
        assertEquals(NOW.minusSeconds(7200).toEpochMilli(), item.at());
    }

    @Test
    void theCallersOwnLatestChangeIsNotListed() {
        FakeSource source = new FakeSource().with(change("p-1", CaseEventPayload.PRIORITY_CHANGED, ActorTrust.VERIFIED, CALLER, "Me",
                NOW.minusSeconds(60)));

        assertEquals(0, section(source).count(), "a change by the caller is not a change by others");
    }

    @Test
    void aLatestEventWithoutAVerifiedStaffActorIsNotListed() {
        for (ActorTrust trust : List.of(ActorTrust.PLAYER, ActorTrust.SYSTEM, ActorTrust.UNVERIFIED)) {
            FakeSource source = new FakeSource().with(change("p-1", CaseEventPayload.STATUS_CHANGED, trust, OTHER, null,
                    NOW.minusSeconds(60)));
            assertEquals(0, section(source).count(), "an event with actor trust " + trust + " must not be listed");
        }
        FakeSource noActor = new FakeSource().with(change("p-1", CaseEventPayload.STATUS_CHANGED, ActorTrust.VERIFIED, null, null,
                NOW.minusSeconds(60)));
        assertEquals(0, section(noActor).count(), "an event without an actor oid must not be listed");
    }

    @Test
    void aChangeOlderThanTheWindowIsNotListed() {
        FakeSource source = new FakeSource().with(change("p-1", CaseEventPayload.STATUS_CHANGED, ActorTrust.VERIFIED, OTHER, "Mia K.",
                NOW.minus(Duration.ofHours(25))));

        assertEquals(0, section(source).count(), "an event 25 hours old is outside the rolling 24 hours");
    }

    @Test
    void theCountIsNotCappedAndTheItemsAreTheTenNewestFirst() {
        FakeSource source = new FakeSource();
        for (int i = 0; i < 14; i++) {
            source.with(change("p-" + i, CaseEventPayload.NOTE_ADDED, ActorTrust.VERIFIED, OTHER, "Mia K.", NOW.minusSeconds(100L * i + 60)));
        }

        InboxDTO.ChangedSection section = section(source);

        assertEquals(14, section.count(), "the count is the full number of rows");
        assertEquals(10, section.items().size(), "at most ten items");
        List<Long> ats = section.items().stream().map(InboxDTO.ChangedItem::at).toList();
        assertEquals(ats.stream().sorted(java.util.Comparator.reverseOrder()).toList(), ats, "newest first");
        assertEquals(NOW.minusSeconds(60).toEpochMilli(), ats.getFirst());
    }

    @Test
    void anAppealFilingOrDecisionIsAnAppealRowWithTheAppealIdentifier() {
        String appeal = "a1000000-0000-4000-8000-000000000001";
        FakeSource source = new FakeSource().with(appealChange("p-1", CaseEventPayload.APPEAL_DECIDED, appeal, OTHER,
                NOW.minusSeconds(60)));

        InboxDTO.ChangedItem item = section(source).items().getFirst();

        assertEquals(InboxChangeKind.APPEAL, item.kind());
        assertEquals(appeal, item.id());
        assertEquals(InboxChange.APPEAL_DECIDED, item.change());
    }

    @Test
    void anAnonymizedStaffMemberIsStillListedWithANullName() {
        FakeSource source = new FakeSource().with(change("p-1", CaseEventPayload.TAGS_CHANGED, ActorTrust.VERIFIED, OTHER, null,
                NOW.minusSeconds(60)));

        InboxDTO.ChangedSection section = section(source);

        assertEquals(1, section.count(), "the row stays");
        assertNull(section.items().getFirst().byName(), "a former staff member is shown without a name");
    }

    @Test
    void theChangeValueIsAFixedEnumAndAnyOtherEventIsOther() {
        assertEquals(InboxChange.STATUS, InboxChange.of(CaseEventPayload.STATUS_CHANGED));
        assertEquals(InboxChange.ASSIGNEE, InboxChange.of(CaseEventPayload.ASSIGNEE_CHANGED));
        assertEquals(InboxChange.PRIORITY, InboxChange.of(CaseEventPayload.PRIORITY_CHANGED));
        assertEquals(InboxChange.TAGS, InboxChange.of(CaseEventPayload.TAGS_CHANGED));
        assertEquals(InboxChange.NOTE, InboxChange.of(CaseEventPayload.NOTE_EDITED));
        assertEquals(InboxChange.NOTE, InboxChange.of(CaseEventPayload.NOTE_REDACTED));
        assertEquals(InboxChange.MESSAGE, InboxChange.of(CaseEventPayload.MESSAGE_DELETED));
        assertEquals(InboxChange.MESSAGE, InboxChange.of(CaseEventPayload.MESSAGE_REDACTED));
        assertEquals(InboxChange.APPEAL_FILED, InboxChange.of(CaseEventPayload.APPEAL_FILED));
        assertEquals(InboxChange.OTHER, InboxChange.of(CaseEventPayload.CASE_BULK_EDITED));
        assertEquals(InboxChange.OTHER, InboxChange.of("something.unknown"));
    }

    @Test
    void theSectionIsPresentEvenWhenNothingChanged() {
        InboxDTO.ChangedSection section = section(new FakeSource());

        assertEquals(0, section.count());
        assertEquals(List.of(), section.items(), "the section always carries its item list");
    }
}
