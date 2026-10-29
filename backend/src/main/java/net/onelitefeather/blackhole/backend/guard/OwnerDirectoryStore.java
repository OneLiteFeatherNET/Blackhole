package net.onelitefeather.blackhole.backend.guard;

import java.util.Set;

/** Owner hashes the login check needs by name: those with enough playtime, and those of linked staff. */
public interface OwnerDirectoryStore {

    /** Owners whose recorded, ended sessions add up to at least {@code minPlaytimeMillis}. */
    Set<String> knownOwners(long minPlaytimeMillis);

    /** Owners that have been seen on a player UUID that is linked to a staff account. */
    Set<String> staffOwners();
}
