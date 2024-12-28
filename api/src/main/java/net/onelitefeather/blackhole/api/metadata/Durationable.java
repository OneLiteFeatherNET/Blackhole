package net.onelitefeather.blackhole.api.metadata;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;

public interface Durationable extends Metadata {

    String META_DATA_KEY_DURATION = "duration";

    /**
     * The duration of the object.
     *
     * @return the duration
     */
    @NotNull Duration duration();

    /**
     * Checks if the object has a duration.
     * @return true if the object has a duration
     */
    default boolean durationable() {
        return hasMetaData(META_DATA_KEY_DURATION);
    }
}
