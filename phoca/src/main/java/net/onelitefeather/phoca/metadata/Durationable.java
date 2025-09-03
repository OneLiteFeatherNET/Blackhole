package net.onelitefeather.phoca.metadata;

import java.time.Duration;

public interface Durationable extends Metadata {

    String META_DATA_KEY_DURATION = "duration";

    /**
     * The duration of the object.
     *
     * @return the duration
     */
    Duration duration();

    default boolean durationable() {
        return hasMetaData(META_DATA_KEY_DURATION);
    }
}
