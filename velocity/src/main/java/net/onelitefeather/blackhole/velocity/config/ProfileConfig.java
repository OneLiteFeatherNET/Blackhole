package net.onelitefeather.blackhole.velocity.config;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;

/**
 * The {@code profile} section of {@code config.json}: how long a player's profile read from the backend may take.
 * A login is decided on the read, so it may wait longer ({@value #DEFAULT_LOGIN_TIMEOUT_MILLIS} ms by default); a
 * chat message or a server connect waits less ({@value #DEFAULT_CHAT_TIMEOUT_MILLIS} ms). A read that runs past its
 * timeout is treated as unavailable, like any other failed read.
 */
public final class ProfileConfig {

    private static final int DEFAULT_LOGIN_TIMEOUT_MILLIS = 2000;
    private static final int DEFAULT_CHAT_TIMEOUT_MILLIS = 1000;

    /** How long the profile read of a login may take. */
    private int loginTimeoutMillis;

    /** How long the profile read of a chat message or a server connect may take. */
    private int chatTimeoutMillis;

    public ProfileConfig() {
        this.loginTimeoutMillis = DEFAULT_LOGIN_TIMEOUT_MILLIS;
        this.chatTimeoutMillis = DEFAULT_CHAT_TIMEOUT_MILLIS;
    }

    /**
     * @return how long a login's profile read may take; the default of 2 seconds if the setting is not positive
     */
    public @NotNull Duration getLoginTimeout() {
        return Duration.ofMillis(this.loginTimeoutMillis > 0 ? this.loginTimeoutMillis : DEFAULT_LOGIN_TIMEOUT_MILLIS);
    }

    /**
     * @return how long a chat or connect profile read may take; the default of 1 second if the setting is not positive
     */
    public @NotNull Duration getChatTimeout() {
        return Duration.ofMillis(this.chatTimeoutMillis > 0 ? this.chatTimeoutMillis : DEFAULT_CHAT_TIMEOUT_MILLIS);
    }
}
