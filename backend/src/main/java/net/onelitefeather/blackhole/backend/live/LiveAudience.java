package net.onelitefeather.blackhole.backend.live;

/**
 * Who may hear about a change, and who listens. A {@link #PLAYER} change reaches the player's streams and the staff streams of
 * the case; a {@link #STAFF} change (an internal note, a seen marker) reaches only the staff streams.
 */
public enum LiveAudience {
    PLAYER,
    STAFF
}
