package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.evasion.ClientFingerprintStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** An in-memory {@link ClientFingerprintStore}. */
public final class FakeClientFingerprintStore implements ClientFingerprintStore {

    private final List<Sighting> rows = new ArrayList<>();

    public List<Sighting> all() {
        return List.copyOf(this.rows);
    }

    @Override
    public void record(String fingerprint, String scheme, String owner, long now) {
        for (int i = 0; i < this.rows.size(); i++) {
            Sighting row = this.rows.get(i);
            if (row.fingerprint().equals(fingerprint) && row.owner().equals(owner)) {
                this.rows.set(i, new Sighting(fingerprint, owner, row.firstSeen(), now));
                return;
            }
        }
        this.rows.add(new Sighting(fingerprint, owner, now, now));
    }

    @Override
    public List<Sighting> ofFingerprint(String fingerprint, long since) {
        return ofFingerprints(List.of(fingerprint), since);
    }

    @Override
    public List<String> fingerprintsOf(String owner, long since) {
        return this.rows.stream().filter(row -> row.owner().equals(owner) && row.lastSeen() >= since).map(Sighting::fingerprint).toList();
    }

    @Override
    public List<Sighting> ofFingerprints(Collection<String> fingerprints, long since) {
        return this.rows.stream().filter(row -> fingerprints.contains(row.fingerprint()) && row.lastSeen() >= since).toList();
    }

    @Override
    public int deleteBefore(long cutoff) {
        int before = this.rows.size();
        this.rows.removeIf(row -> row.lastSeen() < cutoff);
        return before - this.rows.size();
    }

    @Override
    public int deleteByOwner(String owner) {
        int before = this.rows.size();
        this.rows.removeIf(row -> row.owner().equals(owner));
        return before - this.rows.size();
    }
}
