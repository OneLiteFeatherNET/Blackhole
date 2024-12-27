package net.onelitefeather.blackhole.api.template;

import net.onelitefeather.blackhole.api.metadata.Metadata;
import net.onelitefeather.blackhole.api.punish.PunishType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

record PunishTemplateDTO(
        @NotNull Map<String, Object> metaData,
        @NotNull String reason,
        @NotNull PunishType type,
        @NotNull UUID identifier
) implements PunishTemplate {

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
    public boolean translatable() {
        return hasMetaData(META_DATA_KEY_TRANSLATABLE);
    }

    @Override
    public long creationDate() {
        return Optional.ofNullable(this.metaData.get(Metadata.META_DATA_KEY_CREATION_DATE)).map(Long.class::cast).orElseThrow();
    }

    @Override
    public long updateDate() {
        return Optional.ofNullable(this.metaData.get(Metadata.META_DATA_KEY_UPDATE_DATE)).map(Long.class::cast).orElseThrow();
    }

    @Override
    public Duration duration() {
        return Optional.ofNullable(this.metaData.get(META_DATA_KEY_DURATION)).map(Duration.class::cast).orElseThrow();
    }
}
