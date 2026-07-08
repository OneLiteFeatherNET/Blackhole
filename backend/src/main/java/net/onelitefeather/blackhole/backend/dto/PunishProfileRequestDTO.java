package net.onelitefeather.blackhole.backend.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Request body for creating/updating a punishment profile. The tenant is taken from the URL,
 * not this body.
 */
@Serdeable
@ReflectiveAccess
public record PunishProfileRequestDTO(
        @NonNull @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "Owner must be a sha-512 hash") String owner,
        @Nullable Optional<PunishEntryDTO> activeChatBan,
        @Nullable Optional<PunishEntryDTO> activeBan,
        @Nullable List<PunishEntryDTO> history,
        @NonNull Map<String, Object> metaData
) {
}
