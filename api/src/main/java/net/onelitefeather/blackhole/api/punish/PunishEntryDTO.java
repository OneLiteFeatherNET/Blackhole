package net.onelitefeather.blackhole.api.punish;

import net.onelitefeather.blackhole.api.metadata.Metadata;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Represents a punish entry which can be used to store the punishment in the database.
 *
 * @param identifier   the identifier of the punishment
 * @param type         the type of the punishment
 * @param source       the source of the punishment
 * @param metaData     the metadata of the punishment
 * @author theEvilReaper
 * @version 1.0.0
 * @since 1.0.0
 */
record PunishEntryDTO(
        @NotNull String identifier,
        @NotNull PunishType type,
        @NotNull UUID source,
        @NotNull Map<String, Object> metaData
) implements PunishEntry {

    @Override
    public void addMetaData(@NotNull String key, @NotNull Object value) {
        this.metaData.put(key, value);
    }

    @Override
    public void removeMetaData(@NotNull String key) {
        this.metaData.remove(key);
    }

    @Override
    public boolean hasMetaData(@NotNull String key) {
        return this.metaData.containsKey(key);
    }

    @Override
    public @NotNull Optional<@Nullable Object> getMetaData(@NotNull String key) {
        return Optional.ofNullable(this.metaData.get(key));
    }

    @Override
    public long creationDate() {
        return Optional.ofNullable(this.metaData.get(Metadata.META_DATA_KEY_CREATION_DATE)).map(Long.class::cast).orElseThrow();
    }

    @Override
    public long updateDate() {
        return Optional.ofNullable(this.metaData.get(Metadata.META_DATA_KEY_UPDATE_DATE)).map(Long.class::cast).orElseThrow();
    }
}
