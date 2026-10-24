package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.casetimeline.CaseChanges;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventDraft;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventRecord;
import net.onelitefeather.blackhole.backend.evidence.share.EvidenceShareStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The share table in memory with the semantics of the SQL: the active twin is a repeat, a revoke happens once, row and event together. */
public final class InMemoryShareStore implements EvidenceShareStore {

    private final InMemoryCaseEventRepository log;
    private final List<Share> rows = new ArrayList<>();
    private final java.util.Map<String, UUID> appeals = new java.util.HashMap<>();

    public InMemoryShareStore(InMemoryCaseEventRepository log) {
        this.log = log;
    }

    /** The punishment has this appeal. */
    public void givenAppeal(String punishmentIdentifier, UUID appealId) {
        this.appeals.put(punishmentIdentifier, appealId);
    }

    public List<Share> rows() {
        return this.rows;
    }

    private CaseChanges.Appended last() {
        CaseEventRecord stored = this.log.all().getLast();
        return new CaseChanges.Appended(stored.identifier(), stored.seq(), stored.type());
    }

    @Override
    public Created create(Share share, CaseEventDraft event) {
        for (Share existing : this.rows) {
            if (existing.active() && existing.appealId().equals(share.appealId()) && existing.evidenceId().equals(share.evidenceId())
                    && existing.selection().equals(share.selection())) {
                return new Created(existing, null, true);
            }
        }
        this.rows.add(share);
        this.log.append(event);
        return new Created(share, last(), false);
    }

    @Override
    public Optional<CaseChanges.Appended> revoke(UUID shareId, String punishmentIdentifier, long now, UUID revokedByOid, String reason,
                                                 CaseEventDraft event) {
        for (int i = 0; i < this.rows.size(); i++) {
            Share share = this.rows.get(i);
            if (share.shareId().equals(shareId) && share.punishmentIdentifier().equals(punishmentIdentifier) && share.active()) {
                this.rows.set(i, new Share(share.shareId(), share.punishmentIdentifier(), share.appealId(), share.evidenceId(), share.mode(),
                        share.selection(), share.appellantSpeaker(), share.confirmedMinimised(), share.sharedByOid(), share.sharedAt(), now,
                        revokedByOid, reason));
                this.log.append(event);
                return Optional.of(last());
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<Share> find(UUID shareId, String punishmentIdentifier) {
        return this.rows.stream().filter(s -> s.shareId().equals(shareId) && s.punishmentIdentifier().equals(punishmentIdentifier)).findFirst();
    }

    @Override
    public List<Share> list(String punishmentIdentifier) {
        return this.rows.stream().filter(s -> s.punishmentIdentifier().equals(punishmentIdentifier)).toList();
    }

    @Override
    public Optional<UUID> latestAppeal(String punishmentIdentifier) {
        return Optional.ofNullable(this.appeals.get(punishmentIdentifier));
    }
}
