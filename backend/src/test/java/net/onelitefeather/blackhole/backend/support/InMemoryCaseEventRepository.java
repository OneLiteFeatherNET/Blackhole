package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.casetimeline.CaseEventDraft;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventPayloads;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventReader;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventRecord;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventRepository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A case event log in memory with the semantics of the database one: a dedupe key inserts once,
 * {@code seq} counts up, and a failure can be injected.
 */
public final class InMemoryCaseEventRepository implements CaseEventRepository, CaseEventReader {

    private final List<CaseEventRecord> rows = new ArrayList<>();
    private final Set<String> dedupeKeys = new HashSet<>();
    private long nextSeq = 1;
    private RuntimeException failure;

    /** Makes every append throw until {@link #recover()}. */
    public void failAppends(RuntimeException failure) {
        this.failure = failure;
    }

    public void recover() {
        this.failure = null;
    }

    @Override
    public boolean append(CaseEventDraft draft) {
        if (this.failure != null) {
            throw this.failure;
        }
        if (draft.dedupeKey() != null && !this.dedupeKeys.add(draft.dedupeKey())) {
            return false;
        }
        this.rows.add(new CaseEventRecord(UUID.randomUUID(), this.nextSeq++, draft.punishmentIdentifier(), draft.type(),
                draft.payload().version(), draft.occurredAt(), draft.origin(), draft.actor().trust(), draft.actor().actorOid(),
                draft.actor().recordedByOid(), draft.actor().actorSource(), draft.subjectId(), "STAFF",
                CaseEventPayloads.parse(CaseEventPayloads.toJson(draft.payload())), null, draft.dedupeKey()));
        return true;
    }

    @Override
    public int redact(String punishmentIdentifier, String subjectId, long redactedAt, UUID redactedByOid) {
        throw new UnsupportedOperationException("not needed by the unit tests");
    }

    @Override
    public int deleteForPunishment(String punishmentIdentifier) {
        int before = this.rows.size();
        this.rows.removeIf(row -> row.punishmentIdentifier().equals(punishmentIdentifier));
        return before - this.rows.size();
    }

    @Override
    public List<CaseEventRecord> timeline(String punishmentIdentifier) {
        return this.rows.stream().filter(row -> row.punishmentIdentifier().equals(punishmentIdentifier))
                .sorted(CaseEventRecord.TIMELINE_ORDER).toList();
    }

    /** Every event of every punishment, in the order they were appended. */
    public List<CaseEventRecord> all() {
        return List.copyOf(this.rows);
    }

    /** The events of one type of a punishment, in timeline order. */
    public List<CaseEventRecord> ofType(String punishmentIdentifier, String type) {
        return timeline(punishmentIdentifier).stream().filter(row -> row.type().equals(type)).toList();
    }
}
