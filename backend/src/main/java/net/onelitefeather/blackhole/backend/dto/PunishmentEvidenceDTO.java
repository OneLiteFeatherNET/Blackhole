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

/**
 * A single piece of evidence attached to a punishment. Deliberately carries a hash/reference
 * plus a short-lived {@code retentionExpiresAt}, not the full raw content (e.g. a chat message),
 * so evidence storage stays DSGVO-compliant by default rather than becoming a second full copy
 * of chat logs.
 *
 * @param tenantId             the tenant the evidence belongs to
 * @param identifier           the identifier of the evidence entry, {@code null} when creating
 * @param punishmentIdentifier the identifier of the punishment this evidence is attached to
 * @param evidenceType         where this evidence originated from
 * @param referenceId          an external reference (e.g. a chat log message id), if any
 * @param capturedContentHash  a hash of the captured content, if any
 * @param retentionExpiresAt   epoch millis after which this evidence should be erased, if bounded
 * @param metaData             the metadata of the evidence entry
 */
@Serdeable
@ReflectiveAccess
public record PunishmentEvidenceDTO(
        @NonNull UUID tenantId,
        @Nullable UUID identifier,
        @NonNull @NotBlank String punishmentIdentifier,
        @NonNull EvidenceType evidenceType,
        @Nullable String referenceId,
        @Nullable String capturedContentHash,
        @Nullable Long retentionExpiresAt,
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
