package net.onelitefeather.blackhole.backend.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import net.onelitefeather.blackhole.api.metadata.Metadata;
import net.onelitefeather.blackhole.backend.response.PunishProfileResponse;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Serdeable
@ReflectiveAccess
public record PunishProfileDTO(
        @NonNull @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "Owner must be a sha-512 hash") String owner,
        @Nullable Optional<PunishEntryDTO> activeChatBan,
        @Nullable Optional<PunishEntryDTO> activeBan,
        @Nullable List<PunishEntryDTO> history,
        @NonNull Map<String, Object> metaData
) implements Metadata, PunishProfileResponse {

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
    public @NotNull Optional<Object> getMetaData(@NotNull String key) {
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
