package net.onelitefeather.blackhole.api.metadata;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.Map;
import java.util.Optional;

/**
 * The interface defines the basic structure to describe the metadata of an object.
 *
 * @author theEvilReaper
 * @version 1.0.0
 * @since 1.0.0
 */
public interface Metadata {

    String META_DATA_KEY_CREATION_DATE = "creation_date";
    String META_DATA_KEY_UPDATE_DATE = "update_date";

    /**
     * Adds a new metadata entry to the profile.
     *
     * @param key   the key of the metadata
     * @param value the value of the metadata
     */
    void addMetaData(@NotNull String key, @NotNull Object value);

    /**
     * Removes the metadata entry from the profile.
     *
     * @param key the key of the metadata
     */
    void removeMetaData(@NotNull String key);

    /**
     * Checks if the profile has a metadata entry.
     *
     * @param key the key of the metadata
     * @return {@code true} if the profile has the metadata
     */
    boolean hasMetaData(@NotNull String key);

    /**
     * Returns the metadata entry from the profile.
     *
     * @param key the key of the metadata
     * @return the metadata entry
     */
    @NotNull Optional<@Nullable Object> getMetaData(@NotNull String key);

    /**
     * Returns the metadata of the profile.
     *
     * @return the metadata
     */
    @NotNull
    @UnmodifiableView
    Map<String, Object> metaData();

    /**
     * The date when the object was created.
     *
     * @return the creation date
     */
    long creationDate();

    /**
     * The date when the object was updated.
     *
     * @return the update date
     */
    long updateDate();
}
