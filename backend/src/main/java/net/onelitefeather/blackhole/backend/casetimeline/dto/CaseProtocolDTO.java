package net.onelitefeather.blackhole.backend.casetimeline.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.List;
import java.util.UUID;

/**
 * Wire contract of the stored appeal protocols of a case. Staff only ({@code VIEWER} and above). The
 * protocol holds the conversation, so a variant that carries text is never cached and never served to a
 * player session.
 */
@Schema(description = "The stored protocols of the appeals of a case")
@Serdeable
public sealed interface CaseProtocolDTO {

    /**
     * One readable version of an appeal's protocol.
     *
     * @param current      whether it is the active (latest) version of its appeal
     * @param sourceDigest SHA-256 of the source events, as printed in the protocol's completeness check
     */
    @Schema(name = "CaseProtocolVersion")
    @Serdeable
    @ReflectiveAccess
    record Version(
            @NonNull UUID appealId, int version, @NonNull UUID evidenceId, boolean current, int entryCount,
            @Nullable Long firstSeq, @Nullable Long lastSeq, @NonNull String sourceDigest, long createdAt
    ) {
    }

    /** The readable versions of a case, newest first. */
    @Schema(name = "CaseProtocolList")
    @Serdeable
    @ReflectiveAccess
    record ListResponse(
            // Serde omits empty collections by default; an empty list must still say items: [].
            @NonNull @JsonInclude(JsonInclude.Include.ALWAYS) List<Version> items) implements CaseProtocolDTO {
    }

    /** The human-readable rendering of one version. */
    @Schema(name = "CaseProtocolText")
    @Serdeable
    @ReflectiveAccess
    record TextResponse(@NonNull UUID appealId, int version, @NonNull UUID evidenceId, @NonNull String text) implements CaseProtocolDTO {
    }

    /** A file the controller serves as an attachment. */
    @Schema(hidden = true)
    @Serdeable
    @ReflectiveAccess
    record Download(@NonNull String fileName, @NonNull String contentType, byte @NonNull [] content) implements CaseProtocolDTO {
    }

    @Schema(name = "CaseProtocolError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull CaseErrorKind kind) implements CaseProtocolDTO, ErrorResponse {
    }
}
