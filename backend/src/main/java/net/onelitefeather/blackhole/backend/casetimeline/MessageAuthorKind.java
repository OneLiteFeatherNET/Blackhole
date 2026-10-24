package net.onelitefeather.blackhole.backend.casetimeline;

/** Who wrote a conversation message. {@code BOT} and {@code EXTERNAL} are for the Discord channel (a later change). */
public enum MessageAuthorKind {
    PLAYER,
    STAFF,
    BOT,
    EXTERNAL
}
