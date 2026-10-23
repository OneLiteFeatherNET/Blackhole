package net.onelitefeather.blackhole.backend.evidence.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import net.onelitefeather.blackhole.backend.evidence.EvidenceKind;
import net.onelitefeather.blackhole.backend.evidence.EvidenceStatus;
import net.onelitefeather.blackhole.backend.response.ErrorResponse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wire contract of the evidence endpoints. Evidence is staff-only: players learn only
 * {@link CountResponse}, never a {@link Response}.
 */
@Schema(description = "Evidence attached to punishments")
@Serdeable
public sealed interface EvidenceDTO {

    /**
     * Attaches structured or link evidence. The target is either {@code punishment} (full identifier
     * or short id) or {@code owner} together with {@code correlationId} (evidence sent before the
     * punishment exists); exactly one form is valid.
     *
     * @param punishment    the punishment's identifier or short id, e.g. {@code #A1B2C3D4}
     * @param owner         the hashed owner, for evidence that precedes its punishment
     * @param correlationId the client-chosen key the punishment claims the evidence with
     * @param kind          ANTICHEAT, REPLAY, CHAT_LOG or LINK (FILE goes through the upload endpoint)
     * @param sourceSystem  the (unverified) system that sends the evidence
     * @param capturedAt    when the evidence was captured, epoch millis
     * @param reference     the absolute http(s) URL of a REPLAY or LINK
     * @param payload       the kind's structured content; a REPLAY or LINK takes only a {@code description}
     * @param onBehalfOf    the Minecraft UUID of the in-game staff member attaching the evidence; accepted only from
     *                      the {@code PROXY} role (HTTP 400 otherwise). The caller of the token is always recorded.
     */
    @Schema(name = "EvidenceCreateRequest")
    @Serdeable
    @ReflectiveAccess
    record CreateRequest(
            @Nullable String punishment,
            @Nullable @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "Owner must be a sha-512 hash") String owner,
            @Nullable @Size(min = 1, max = 64) String correlationId,
            @NonNull EvidenceKind kind,
            @NonNull @NotBlank @Size(max = 64) String sourceSystem,
            @Nullable Long capturedAt,
            @Nullable String reference,
            @Nullable Map<String, Object> payload,
            @Nullable UUID onBehalfOf
    ) implements EvidenceDTO {
    }

    /**
     * The fields of a file upload, assembled by the controller from the multipart form.
     */
    @Schema(hidden = true)
    @Serdeable
    @ReflectiveAccess
    record UploadRequest(
            @Nullable String punishment,
            @Nullable String owner,
            @Nullable String correlationId,
            @Nullable String sourceSystem,
            @Nullable Long capturedAt,
            @Nullable String description,
            @Nullable UUID onBehalfOf,
            @Nullable String fileName
    ) implements EvidenceDTO {
    }

    /**
     * Metadata of a stored file.
     *
     * @param name        the sanitised display name
     * @param contentType the content type detected from the file's magic bytes
     * @param size        the stored size in bytes
     * @param sha256      the SHA-256 of the stored bytes
     */
    @Schema(name = "EvidenceFile")
    @Serdeable
    @ReflectiveAccess
    record FileInfo(
            @NonNull String name,
            @NonNull String contentType,
            long size,
            @NonNull String sha256
    ) {
    }

    /**
     * One evidence item.
     *
     * @param identifier           the evidence id
     * @param punishmentIdentifier the punishment, or {@code null} while the item waits for one
     * @param owner                the hashed owner
     * @param correlationId        the pending claim key, while pending
     * @param kind                 what the item is
     * @param sourceSystem         the (unverified) system that sent it
     * @param capturedAt           when it was captured, epoch millis
     * @param createdAt            when it was attached, epoch millis
     * @param createdBy            the attributed actor: the caller's {@code oid}, the in-game staff UUID the proxy
     *                             vouched for, or whatever was stored before SSO
     * @param recordedByOid        the {@code oid} of the verified principal that attached it, {@code null} before SSO
     * @param verified             whether {@code recordedByOid} is present
     * @param reference            the URL of a REPLAY or LINK
     * @param payload              the structured content
     * @param file                 the stored file's metadata
     * @param status               VALID, REVOKED or LIFTED
     * @param statusChangedAt      when it became REVOKED or LIFTED
     * @param liftedByAppeal       the appeal that lifted the punishment
     */
    @Schema(name = "EvidenceResponse")
    @Serdeable
    @ReflectiveAccess
    record Response(
            @NonNull UUID identifier,
            @Nullable String punishmentIdentifier,
            @NonNull String owner,
            @Nullable String correlationId,
            @NonNull EvidenceKind kind,
            @NonNull String sourceSystem,
            @Nullable Long capturedAt,
            long createdAt,
            @Nullable UUID createdBy,
            @Nullable UUID recordedByOid,
            boolean verified,
            @Nullable String reference,
            @Nullable Map<String, Object> payload,
            @Nullable FileInfo file,
            @NonNull EvidenceStatus status,
            @Nullable Long statusChangedAt,
            @Nullable UUID liftedByAppeal
    ) implements EvidenceDTO {
    }

    /** One page of evidence items. */
    @Schema(name = "EvidencePage")
    @Serdeable
    @ReflectiveAccess
    record PageResponse(
            // Serde omits empty collections by default; an empty page must still say items: [].
            @NonNull @JsonInclude(JsonInclude.Include.ALWAYS) List<Response> items,
            int page,
            int size,
            long totalSize,
            int totalPages
    ) implements EvidenceDTO {
    }

    /** The number of valid evidence items: the only thing a player may learn. */
    @Schema(name = "EvidenceCount")
    @Serdeable
    @ReflectiveAccess
    record CountResponse(long count) implements EvidenceDTO {
    }

    /** The result of an admin delete. */
    @Schema(name = "EvidenceDeleteResponse")
    @Serdeable
    @ReflectiveAccess
    record DeleteResponse(int deleted) implements EvidenceDTO {
    }

    /**
     * A stored file ready to stream. Never serialised: the controller turns it into the file
     * response, so it is not {@code @Serdeable} and hidden from the API schema.
     *
     * @param stream      the requested bytes; the caller closes it
     * @param length      how many bytes the stream yields
     * @param start       the first byte served
     * @param end         the last byte served (inclusive)
     * @param total       the size of the whole file
     * @param partial     whether this is a part of the file (status 206)
     * @param contentType the sniffed content type
     * @param fileName    the sanitised display name
     */
    @Schema(hidden = true)
    record Download(
            @NonNull java.io.InputStream stream,
            long length,
            long start,
            long end,
            long total,
            boolean partial,
            @NonNull String contentType,
            @NonNull String fileName
    ) implements EvidenceDTO {
    }

    /**
     * The requested byte range starts behind the end of the file (416).
     *
     * @param size the size of the file, for the {@code Content-Range: bytes *}{@code /size} header
     */
    @Schema(hidden = true)
    record OutOfRange(long size) implements EvidenceDTO {
    }

    @Schema(name = "EvidenceError")
    @Serdeable
    @ReflectiveAccess
    record Error(@NonNull String errorMessage, @NonNull EvidenceErrorKind kind) implements EvidenceDTO, ErrorResponse {
    }
}
