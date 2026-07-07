package net.onelitefeather.blackhole.backend.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import net.onelitefeather.phoca.metadata.Metadata;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Serdeable
@ReflectiveAccess
public record TenantDTO(
        @Nullable UUID identifier,
        @NonNull @NotBlank String name,
        @NonNull @NotBlank String slug,
        @NonNull TenantStatus status,
        @NonNull Map<String, Object> metaData
) implements Metadata {

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
    public long creationDate() {
        return Optional.ofNullable(this.metaData.get(Metadata.META_DATA_KEY_CREATION_DATE)).map(Long.class::cast).orElseThrow();
    }

    @Override
    public long updateDate() {
        return Optional.ofNullable(this.metaData.get(Metadata.META_DATA_KEY_UPDATE_DATE)).map(Long.class::cast).orElseThrow();
    }
}
