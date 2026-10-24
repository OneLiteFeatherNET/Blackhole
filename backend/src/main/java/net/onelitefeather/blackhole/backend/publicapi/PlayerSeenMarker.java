package net.onelitefeather.blackhole.backend.publicapi;

/** The player's seen marker of a case: how far the player's conversation read has got. */
public interface PlayerSeenMarker {

    /** Marks nothing: for a service built without a database, such as a test of the message rules. */
    PlayerSeenMarker NONE = (punishmentIdentifier, seq) -> false;

    /**
     * Moves the marker of the case to {@code seq} when that is further on than it is now. Never moves it back.
     *
     * @return whether the marker moved
     */
    boolean moveTo(String punishmentIdentifier, long seq);
}
