package net.onelitefeather.blackhole.backend.profile.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Pattern;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishEntryDTO;
import net.onelitefeather.phoca.metadata.Metadata;

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
    public void addMetaData(String key, Object value) {
        this.metaData.put(key, value);
    }

    @Override
    public void removeMetaData(String key) {
        this.metaData.remove(key);
    }

    @Override
    public boolean hasMetaData(String key) {
        return this.metaData.containsKey(key);
    }

    @Override
    public Optional<Object> getMetaData(String key) {
        return Optional.ofNullable(this.metaData.get(key));
    }

    @Override
    public Optional<PunishEntryDTO> activeChatBan() {
        return this.activeChatBan;
    }

    @Override
    public Optional<PunishEntryDTO> activeBan() {
        return this.activeBan;
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
