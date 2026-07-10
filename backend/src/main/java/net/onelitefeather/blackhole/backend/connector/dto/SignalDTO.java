package net.onelitefeather.blackhole.backend.connector.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.Map;

/**
 * A generic external signal (e.g. an anticheat flag) ingested via {@code POST /signal}.
 * Deliberately opaque - Blackhole doesn't know or care what {@code signalType} values a given
 * connector uses; it's published as a domain event and Phase 5's ELO system (or any other
 * consumer) decides what to do with it. This is what keeps the connector framework generic
 * rather than anticheat-specific.
 *
 * @param owner      SHA-512 hash of the affected player
 * @param signalType a connector-chosen identifier, e.g. {@code anticheat.flag}
 * @param payload    signal-specific data
 * @param metaData   extensible metadata
 */
@Serdeable
@ReflectiveAccess
public record SignalDTO(
        @NonNull @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "owner must be a sha-512 hash") String owner,
        @NonNull @NotBlank String signalType,
        @NonNull Map<String, Object> payload,
        @NonNull Map<String, Object> metaData
) {
}
