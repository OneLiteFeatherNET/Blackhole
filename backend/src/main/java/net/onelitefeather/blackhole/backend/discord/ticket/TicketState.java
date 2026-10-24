package net.onelitefeather.blackhole.backend.discord.ticket;

import io.micronaut.serde.annotation.Serdeable;

/** Where a Discord ticket is in its life. Stored as text. */
@Serdeable
public enum TicketState {

    /** The ticket exists on the case; the channel is not reported yet (a crash between the two steps lands here). */
    PENDING_CHANNEL,

    /** The channel exists and is in use. */
    OPEN,

    /** The bot started closing: locked, rescan running. */
    CLOSING,

    /** Closed: normally with a verified transcript, after three failed rescans without. Never re-opened. */
    CLOSED,

    /** Moved to the archive category; only with a stored protocol. */
    ARCHIVED,

    /** The channel is gone. */
    DELETED;

    /** Whether the ticket still takes part in the one-open-ticket-per-appeal rule. */
    public boolean live() {
        return this == PENDING_CHANNEL || this == OPEN || this == CLOSING;
    }
}
