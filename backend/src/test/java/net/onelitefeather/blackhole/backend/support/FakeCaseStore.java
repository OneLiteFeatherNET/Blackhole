package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.casetimeline.CaseActor;
import net.onelitefeather.blackhole.backend.casetimeline.CaseChanges;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventDraft;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventOrigin;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventPayload;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventRecord;
import net.onelitefeather.blackhole.backend.casetimeline.CaseMeta;
import net.onelitefeather.blackhole.backend.casetimeline.CaseReader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The case row and its changes in memory, with the semantics of the SQL ones (a stale version changes
 * nothing, a redacted note cannot be edited), over an {@link InMemoryCaseEventRepository}. The list,
 * timeline paging and related queries are SQL and are tested against PostgreSQL instead.
 */
public final class FakeCaseStore implements CaseReader, CaseChanges {

    private final InMemoryCaseEventRepository log;
    private final Map<String, CaseMeta> metas = new HashMap<>();
    private final Map<String, Long> versions = new HashMap<>();

    public FakeCaseStore(InMemoryCaseEventRepository log) {
        this.log = log;
    }

    public CaseMeta meta(String punishment) {
        return this.metas.getOrDefault(punishment, CaseMeta.defaults());
    }

    /** Makes the case change behind the caller's back, as a concurrent writer would. */
    public void bumpVersion(String punishment) {
        this.versions.merge(punishment, 1L, Long::sum);
    }

    public boolean hasCase(String punishment) {
        return this.metas.containsKey(punishment);
    }

    @Override
    public Optional<CaseRow> find(String punishmentIdentifier) {
        CaseMeta meta = this.metas.get(punishmentIdentifier);
        return meta == null ? Optional.empty() : Optional.of(new CaseRow(meta, null, this.versions.getOrDefault(punishmentIdentifier, 0L), 0, 0));
    }

    @Override
    public List<Related> related(String owner, String excludedIdentifier, long now, int limit) {
        return List.of();
    }

    @Override
    public List<Entry> timeline(TimelineQuery query) {
        throw new UnsupportedOperationException("SQL, tested against PostgreSQL");
    }

    @Override
    public List<CaseEventRecord> subject(String punishmentIdentifier, String subjectId) {
        return this.log.timeline(punishmentIdentifier).stream().filter(row -> subjectId.equals(row.subjectId())).toList();
    }

    @Override
    public ListPage list(ListQuery query, long now) {
        throw new UnsupportedOperationException("SQL, tested against PostgreSQL");
    }

    @Override
    public void ensureCase(String punishmentIdentifier, long now) {
        this.metas.putIfAbsent(punishmentIdentifier, CaseMeta.defaults());
    }

    @Override
    public List<Appended> update(String punishmentIdentifier, long expectedVersion, long now, CaseActor actor, CaseMeta before, CaseMeta after) {
        if (this.versions.getOrDefault(punishmentIdentifier, 0L) != expectedVersion) {
            return List.of();
        }
        List<Appended> appended = new ArrayList<>();
        for (CaseEventPayload payload : after.diffFrom(before)) {
            this.log.append(new CaseEventDraft(punishmentIdentifier, payload, now, CaseEventOrigin.LIVE, actor, null, null));
            CaseEventRecord stored = this.log.all().getLast();
            appended.add(new Appended(stored.identifier(), stored.seq(), stored.type()));
        }
        this.metas.put(punishmentIdentifier, after);
        this.versions.merge(punishmentIdentifier, 1L, Long::sum);
        return appended;
    }

    @Override
    public Appended editNote(String punishmentIdentifier, String noteId, String text, long now, CaseActor actor) {
        if (subject(punishmentIdentifier, noteId).stream().anyMatch(row -> row.redactedAt() != null)) {
            return null;
        }
        this.log.append(new CaseEventDraft(punishmentIdentifier, new CaseEventPayload.NoteEdited(noteId, text), now, CaseEventOrigin.LIVE,
                actor, noteId, null));
        CaseEventRecord stored = this.log.all().getLast();
        return new Appended(stored.identifier(), stored.seq(), stored.type());
    }
}
