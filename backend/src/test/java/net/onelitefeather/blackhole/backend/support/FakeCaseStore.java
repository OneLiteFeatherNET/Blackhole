package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.casetimeline.ActorTrust;
import net.onelitefeather.blackhole.backend.casetimeline.BanIdCollisionException;
import net.onelitefeather.blackhole.backend.casetimeline.BanIdLeakReason;
import net.onelitefeather.blackhole.backend.casetimeline.BanIdLeakSource;
import net.onelitefeather.blackhole.backend.casetimeline.BanIdReissueReason;
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
    private final Map<String, Long> leaks = new HashMap<>();
    private final Map<String, Integer> generations = new HashMap<>();
    private final java.util.Set<String> noBanId = new java.util.HashSet<>();
    private int collisions;

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
        return meta == null ? Optional.empty() : Optional.of(new CaseRow(meta, null, this.versions.getOrDefault(punishmentIdentifier, 0L), 0, 0,
                this.leaks.get(punishmentIdentifier), this.leaks.containsKey(punishmentIdentifier) ? "OTHER" : null));
    }

    @Override
    public List<Related> related(String owner, String excludedIdentifier, long now, int limit) {
        return List.of();
    }

    @Override
    public List<Entry> timeline(TimelineQuery query) {
        return this.log.timeline(query.punishmentIdentifier()).stream().limit(query.limit()).map(event -> new Entry(event, null)).toList();
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

    /** A punishment that has no Ban ID, so a reissue finds nothing to replace. */
    public void givenNoBanId(String punishment) {
        this.noBanId.add(punishment);
    }

    /** The next {@code count} reissues collide with another punishment's hash. */
    public void collideNext(int count) {
        this.collisions = count;
    }

    public int generation(String punishment) {
        return this.generations.getOrDefault(punishment, 1);
    }

    private Appended last() {
        CaseEventRecord stored = this.log.all().getLast();
        return new Appended(stored.identifier(), stored.seq(), stored.type());
    }

    @Override
    public List<Appended> appendMessage(CaseEventDraft message, boolean promoteWaiting) {
        if (!this.log.append(message)) {
            return List.of();
        }
        List<Appended> appended = new ArrayList<>(List.of(last()));
        String id = message.punishmentIdentifier();
        ensureCase(id, message.occurredAt());
        CaseMeta before = this.metas.get(id);
        if (promoteWaiting && before.status().equals("WAITING")) {
            this.metas.put(id, new CaseMeta("IN_REVIEW", before.assignee(), before.priority(), before.tags()));
            this.versions.merge(id, 1L, Long::sum);
            this.log.append(new CaseEventDraft(id, new CaseEventPayload.StatusChanged("WAITING", "IN_REVIEW"), message.occurredAt(),
                    CaseEventOrigin.LIVE, CaseActor.system(), null, null));
            appended.add(last());
        }
        return appended;
    }

    @Override
    public Appended editMessage(CaseEventDraft edit) {
        List<CaseEventRecord> versions = subject(edit.punishmentIdentifier(), edit.subjectId());
        if (versions.stream().anyMatch(row -> row.redactedAt() != null || row.type().equals("message.deleted"))
                || !this.log.append(edit)) {
            return null;
        }
        return last();
    }

    @Override
    public LeakMarked markBanIdLeaked(String punishmentIdentifier, BanIdLeakReason reason, BanIdLeakSource source, long now, CaseActor actor) {
        if (this.leaks.containsKey(punishmentIdentifier)) {
            return null;
        }
        this.leaks.put(punishmentIdentifier, now);
        this.log.append(new CaseEventDraft(punishmentIdentifier, new CaseEventPayload.BanIdLeakMarked(reason, source, false), now,
                CaseEventOrigin.LIVE, actor, null, null, CaseEventDraft.PLAYER));
        return new LeakMarked(last(), false);
    }

    @Override
    public Reissued reissueBanId(String punishmentIdentifier, byte[] hash, byte[] encrypted, BanIdReissueReason reason, long now, CaseActor actor) {
        if (this.collisions > 0) {
            this.collisions--;
            throw new BanIdCollisionException("collision", null);
        }
        if (this.noBanId.contains(punishmentIdentifier)) {
            return null;
        }
        int from = generation(punishmentIdentifier);
        this.generations.put(punishmentIdentifier, from + 1);
        this.leaks.remove(punishmentIdentifier);
        this.log.append(new CaseEventDraft(punishmentIdentifier, new CaseEventPayload.BanIdReissued(reason, from, from + 1), now,
                CaseEventOrigin.LIVE, actor, null, null, CaseEventDraft.PLAYER));
        return new Reissued(last(), from, from + 1);
    }

    @Override
    public List<Erased> erasePlayerMessages(String punishmentIdentifier, String reason, long now, java.util.UUID redactedByOid, CaseActor actor) {
        List<Erased> erased = new ArrayList<>();
        for (CaseEventRecord row : this.log.timeline(punishmentIdentifier)) {
            if (row.type().equals("message.added") && row.actorTrust() == ActorTrust.PLAYER && row.redactedAt() == null) {
                this.log.redact(punishmentIdentifier, row.subjectId(), now, redactedByOid);
                this.log.append(new CaseEventDraft(punishmentIdentifier, new CaseEventPayload.MessageRedacted(row.subjectId(), reason), now,
                        CaseEventOrigin.LIVE, actor, row.subjectId(), "message.redacted:" + row.subjectId(), row.visibility()));
                erased.add(new Erased(row.subjectId(), last(), row.visibility()));
            }
        }
        return erased;
    }
}
