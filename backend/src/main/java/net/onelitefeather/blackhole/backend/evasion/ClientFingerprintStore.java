package net.onelitefeather.blackhole.backend.evasion;

import java.util.Collection;
import java.util.List;

/** Persistence of client fingerprints: the keyed hash and the hashed owner, nothing else about the client. */
public interface ClientFingerprintStore {

    /** One owner seen with one fingerprint. */
    record Sighting(String fingerprint, String owner, long firstSeen, long lastSeen) {
    }

    /** Inserts the owner's sighting of the fingerprint, or updates its last seen time and counter. */
    void record(String fingerprint, String scheme, String owner, long now);

    /** Every owner seen with the fingerprint at or after {@code since}. */
    List<Sighting> ofFingerprint(String fingerprint, long since);

    /** The fingerprints of one owner seen at or after {@code since}. */
    List<String> fingerprintsOf(String owner, long since);

    /** The sightings of any of these fingerprints at or after {@code since}. */
    List<Sighting> ofFingerprints(Collection<String> fingerprints, long since);

    /** Deletes sightings last seen before {@code cutoff}. */
    int deleteBefore(long cutoff);

    /** Deletes every sighting of a player (erasure). */
    int deleteByOwner(String owner);
}
