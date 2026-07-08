package net.onelitefeather.blackhole.backend.dto;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A player report. {@code status}/{@code createdAt}/{@code updatedAt}/{@code resolvedBy}/
 * {@code resolutionNote} are server-controlled - a submission simply leaves them unset/default.
 *
 * @param identifier         the identifier of the report, {@code null} when submitting
 * @param reporterHash       SHA-512 hash of the reporting player
 * @param reportedHash       SHA-512 hash of the reported player
 * @param category           what kind of behavior this report is about
 * @param description        a bounded free-text description
 * @param evidenceReferences optional references to {@code PunishmentEvidenceEntity} entries
 * @param status             the current lifecycle state
 * @param createdAt          epoch millis when the report was submitted
 * @param updatedAt          epoch millis of the last status change
 * @param resolvedBy         the staff/system identity that resolved this report, if resolved
 * @param resolutionNote     a note explaining the resolution, if resolved
 * @param metaData           extensible metadata
 */
@Serdeable
@ReflectiveAccess
public record ReportDTO(
        @Nullable UUID identifier,
        @NonNull @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "reporterHash must be a sha-512 hash") String reporterHash,
        @NonNull @NotBlank @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "reportedHash must be a sha-512 hash") String reportedHash,
        @NonNull ReportCategory category,
        @Nullable @Size(max = 1000) String description,
        @Nullable List<UUID> evidenceReferences,
        @Nullable ReportStatus status,
        long createdAt,
        long updatedAt,
        @Nullable UUID resolvedBy,
        @Nullable @Size(max = 1000) String resolutionNote,
        @NonNull Map<String, Object> metaData
) {
}
