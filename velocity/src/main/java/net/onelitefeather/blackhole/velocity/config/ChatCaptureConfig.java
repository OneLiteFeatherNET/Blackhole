package net.onelitefeather.blackhole.velocity.config;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.format.DateTimeParseException;

/**
 * The optional chat capture ({@code evidence.chatCapture} in {@code config.json}): a short-lived,
 * in-memory buffer of recent public chat that is attached to a punishment as evidence when staff
 * punish a player. <strong>Off by default.</strong> Buffering everyone's public chat is a processing
 * operation of personal data (including third parties' lines) that the operator must enable
 * consciously and justify, for example as a legitimate interest under Art. 6(1)(f) GDPR. The buffer
 * lives in RAM only, is never written to disk or logs and is dropped when the player leaves.
 *
 * <p>Every getter returns a usable value: a missing, out-of-range or unparsable setting falls back to
 * its default instead of failing the plugin.
 */
public final class ChatCaptureConfig {

    /** The most lines one evidence item may carry, in line with the backend's {@code max-chat-lines}. */
    public static final int MAX_LINES = 50;

    private static final int DEFAULT_LINES = 20;
    private static final int DEFAULT_CONTEXT_LINES = 5;
    private static final Duration DEFAULT_MAX_AGE = Duration.ofMinutes(10);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);
    private static final String DEFAULT_SOURCE_SYSTEM = "velocity";
    private static final int MAX_SOURCE_SYSTEM = 64;

    /** Whether public chat is buffered at all. */
    private boolean enabled;

    /** How many of a player's most recent public chat lines are kept (1 to {@link #MAX_LINES}). */
    private int lines;

    /**
     * How many lines of other players that precede the punished player's lines are added so the
     * conversation is intelligible (0 to {@link #MAX_LINES}). They are shown under a per-evidence
     * alias, never a name. {@code 0} switches the third-party context off.
     */
    private int contextLines;

    /** How long a line stays buffered, as an ISO-8601 duration like {@code PT10M}. */
    private String maxAge;

    /** The {@code sourceSystem} the evidence is filed under. */
    private String sourceSystem;

    /** How long the proxy waits for the evidence service, as an ISO-8601 duration like {@code PT2S}. */
    private String timeout;

    public ChatCaptureConfig() {
        this.enabled = false;
        this.lines = DEFAULT_LINES;
        this.contextLines = DEFAULT_CONTEXT_LINES;
        this.maxAge = DEFAULT_MAX_AGE.toString();
        this.sourceSystem = DEFAULT_SOURCE_SYSTEM;
        this.timeout = DEFAULT_TIMEOUT.toString();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * @return the lines kept per player; 20 if the setting is outside 1 to 50
     */
    public int getLines() {
        return lines >= 1 && lines <= MAX_LINES ? lines : DEFAULT_LINES;
    }

    public void setLines(int lines) {
        this.lines = lines;
    }

    /**
     * @return the lines of other players added as context; 5 if the setting is outside 0 to 50
     */
    public int getContextLines() {
        return contextLines >= 0 && contextLines <= MAX_LINES ? contextLines : DEFAULT_CONTEXT_LINES;
    }

    public void setContextLines(int contextLines) {
        this.contextLines = contextLines;
    }

    /**
     * @return how long a line stays buffered; ten minutes if unset, unparsable or not positive
     */
    public @NotNull Duration getMaxAge() {
        return positive(maxAge, DEFAULT_MAX_AGE);
    }

    public void setMaxAge(@NotNull String maxAge) {
        this.maxAge = maxAge;
    }

    /**
     * @return the source system of the evidence; {@code velocity} if unset or longer than the backend accepts
     */
    public @NotNull String getSourceSystem() {
        if (sourceSystem == null || sourceSystem.isBlank() || sourceSystem.strip().length() > MAX_SOURCE_SYSTEM) {
            return DEFAULT_SOURCE_SYSTEM;
        }
        return sourceSystem.strip();
    }

    public void setSourceSystem(@NotNull String sourceSystem) {
        this.sourceSystem = sourceSystem;
    }

    /**
     * @return how long the proxy waits for the evidence service; two seconds if unset, unparsable or not positive
     */
    public @NotNull Duration getTimeout() {
        return positive(timeout, DEFAULT_TIMEOUT);
    }

    public void setTimeout(@NotNull String timeout) {
        this.timeout = timeout;
    }

    private static Duration positive(String value, Duration fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            Duration parsed = Duration.parse(value.strip());
            return parsed.isNegative() || parsed.isZero() ? fallback : parsed;
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }
}
