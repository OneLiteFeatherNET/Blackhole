package net.onelitefeather.blackhole.discord.backend;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The outcome of a backend call. An expected failure is a value, never an exception, so the bot's flows read as
 * tables of cases.
 *
 * @param <T> what a success carries
 */
public sealed interface Result<T> {

    /** @param value what the backend answered */
    record Ok<T>(T value) implements Result<T> {
    }

    /**
     * A refusal or an outage.
     *
     * @param status                the HTTP status, 0 when the backend could not be reached at all
     * @param code                  the backend's error code such as {@code NOT_ELIGIBLE}, or null
     * @param availableFrom         for {@code NOT_YET}: epoch millis, else null
     * @param backendCount          for {@code TRANSCRIPT_MISMATCH}: the backend's tally, else null
     * @param backendLastMessageId  for {@code TRANSCRIPT_MISMATCH}: the highest id the backend holds, else null
     */
    record Failed<T>(int status, @Nullable String code, @Nullable Long availableFrom, @Nullable Integer backendCount,
                     @Nullable String backendLastMessageId) implements Result<T> {

        public static <T> @NotNull Failed<T> of(int status, @Nullable String code) {
            return new Failed<>(status, code, null, null, null);
        }

        /** @return whether the backend (or the edge in front of it) could not serve the call right now, so trying again later is right */
        public boolean unavailable() {
            return this.status == 0 || this.status == 401 || this.status == 429 || this.status >= 500;
        }

        public boolean is(@NotNull String errorCode) {
            return errorCode.equals(this.code);
        }
    }

    default boolean ok() {
        return this instanceof Ok<T>;
    }
}
