package net.onelitefeather.blackhole.backend.casetimeline.protocol;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@link ProtocolStore} over a list, with the same rules the unique indexes of V17 enforce. */
public final class InMemoryProtocolStore implements ProtocolStore {

    public final List<ProtocolVersion> rows = new ArrayList<>();
    List<UUID> candidates = new ArrayList<>();
    /** When set, the next {@link #add} reports that a concurrent run won. */
    boolean loseNextAdd;

    @Override
    public Optional<ProtocolVersion> active(UUID appealId) {
        return this.rows.stream().filter(row -> row.appealId().equals(appealId) && row.status() == ProtocolVersion.Status.ACTIVE).findFirst();
    }

    @Override
    public int nextVersion(UUID appealId) {
        return this.rows.stream().filter(row -> row.appealId().equals(appealId)).mapToInt(ProtocolVersion::version).max().orElse(0) + 1;
    }

    @Override
    public boolean add(NewVersion version) {
        if (this.loseNextAdd) {
            this.loseNextAdd = false;
            return false;
        }
        boolean duplicate = this.rows.stream().anyMatch(row -> row.appealId().equals(version.appealId())
                && row.sourceDigest().equals(version.sourceDigest()) && row.status().readable());
        boolean versionTaken = this.rows.stream().anyMatch(row -> row.appealId().equals(version.appealId()) && row.version() == version.version());
        if (duplicate || versionTaken) {
            return false;
        }
        for (int i = 0; i < this.rows.size(); i++) {
            ProtocolVersion row = this.rows.get(i);
            if (row.appealId().equals(version.appealId()) && row.status() == ProtocolVersion.Status.ACTIVE) {
                this.rows.set(i, with(row, ProtocolVersion.Status.SUPERSEDED, row.evidenceId()));
            }
        }
        this.rows.add(new ProtocolVersion(version.identifier(), version.punishmentIdentifier(), version.appealId(), version.version(),
                version.evidenceId(), version.sourceDigest(), version.entryCount(), version.firstSeq(), version.lastSeq(),
                ProtocolVersion.Status.ACTIVE, version.createdAt()));
        return true;
    }

    @Override
    public List<ProtocolVersion> versionsOf(String punishmentIdentifier) {
        return this.rows.stream().filter(row -> row.punishmentIdentifier().equals(punishmentIdentifier))
                .sorted(Comparator.comparingInt(ProtocolVersion::version).reversed()).toList();
    }

    @Override
    public Optional<ProtocolVersion> byEvidence(UUID evidenceId) {
        return this.rows.stream().filter(row -> evidenceId.equals(row.evidenceId())).findFirst();
    }

    @Override
    public List<ProtocolVersion> purgePending(int limit) {
        return this.rows.stream().filter(row -> row.status() == ProtocolVersion.Status.PURGE_PENDING).limit(limit).toList();
    }

    @Override
    public boolean markPurged(UUID identifier, long now) {
        for (int i = 0; i < this.rows.size(); i++) {
            ProtocolVersion row = this.rows.get(i);
            if (row.identifier().equals(identifier) && row.status() == ProtocolVersion.Status.PURGE_PENDING) {
                this.rows.set(i, with(row, ProtocolVersion.Status.PURGED, null));
                return true;
            }
        }
        return false;
    }

    @Override
    public int settleLostEvidence(UUID appealId, long now) {
        int changed = 0;
        for (int i = 0; i < this.rows.size(); i++) {
            ProtocolVersion row = this.rows.get(i);
            if (row.status().readable() && row.evidenceId() == null && (appealId == null || row.appealId().equals(appealId))) {
                this.rows.set(i, with(row, ProtocolVersion.Status.PURGED, null));
                changed++;
            }
        }
        return changed;
    }

    @Override
    public int markPurgePending(String punishmentIdentifier) {
        int changed = 0;
        for (int i = 0; i < this.rows.size(); i++) {
            ProtocolVersion row = this.rows.get(i);
            if (row.punishmentIdentifier().equals(punishmentIdentifier) && row.status().readable()) {
                this.rows.set(i, with(row, ProtocolVersion.Status.PURGE_PENDING, row.evidenceId()));
                changed++;
            }
        }
        return changed;
    }

    @Override
    public List<String> markPurgePendingForActor(UUID actorOid) {
        List<String> punishments = this.rows.stream().map(ProtocolVersion::punishmentIdentifier).distinct().toList();
        punishments.forEach(this::markPurgePending);
        return punishments;
    }

    @Override
    public List<UUID> candidates(int limit) {
        return this.candidates;
    }

    private static ProtocolVersion with(ProtocolVersion row, ProtocolVersion.Status status, UUID evidenceId) {
        return new ProtocolVersion(row.identifier(), row.punishmentIdentifier(), row.appealId(), row.version(), evidenceId, row.sourceDigest(),
                row.entryCount(), row.firstSeq(), row.lastSeq(), status, row.createdAt());
    }
}
