package net.onelitefeather.blackhole.backend.evidence.controller;

import io.micronaut.security.annotation.Secured;
import net.onelitefeather.blackhole.backend.security.Roles;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.version.annotation.Version;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.http.multipart.StreamingFileUpload;
import io.micronaut.http.server.types.files.StreamedFile;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.validation.Validated;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceDTO;
import net.onelitefeather.blackhole.backend.evidence.service.EvidenceService;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.UUID;

/**
 * Routing and OpenAPI annotations live on {@link EvidenceApi}, which this class implements. File
 * and database work blocks, so every method runs on the blocking executor.
 */
@Validated
@Version(ApiVersion.V1)
@ExecuteOn(TaskExecutors.BLOCKING)
@Controller
public class EvidenceController implements EvidenceApi {

    private final EvidenceService evidenceService;

    /**
     * @param evidenceService attaches, lists, reads and deletes evidence
     */
    @Inject
    public EvidenceController(EvidenceService evidenceService) {
        this.evidenceService = evidenceService;
    }

    @Override
    @Secured({Roles.MODERATOR, Roles.PROXY})
    public HttpResponse<EvidenceDTO> attach(EvidenceDTO.CreateRequest request) {
        return toResponse(this.evidenceService.attach(request));
    }

    @Override
    @Secured({Roles.MODERATOR, Roles.PROXY})
    public HttpResponse<EvidenceDTO> upload(
            StreamingFileUpload file, @Nullable String punishment, @Nullable String owner, @Nullable String correlationId,
            @Nullable String sourceSystem, @Nullable Long capturedAt, @Nullable String description, @Nullable UUID source
    ) {
        EvidenceDTO.UploadRequest request = new EvidenceDTO.UploadRequest(
                punishment, owner, correlationId, sourceSystem, capturedAt, description, source, file.getFilename());
        try (InputStream content = file.asInputStream()) {
            return toResponse(this.evidenceService.attachFile(request, content));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    @Secured({Roles.VIEWER, Roles.PROXY})
    public HttpResponse<EvidenceDTO> listByPunishment(String reference, boolean includeInactive, @Nullable UUID source, Pageable pageable) {
        return toResponse(this.evidenceService.list(reference, includeInactive, pageable, source));
    }

    @Override
    @Secured({Roles.VIEWER, Roles.PROXY})
    public HttpResponse<EvidenceDTO> count(String reference) {
        return toResponse(this.evidenceService.count(reference));
    }

    @Override
    @Secured({Roles.VIEWER, Roles.PROXY})
    public HttpResponse<EvidenceDTO> listByOwner(String owner, boolean includeInactive, @Nullable UUID source, Pageable pageable) {
        return toResponse(this.evidenceService.listByOwner(owner, includeInactive, pageable, source));
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<EvidenceDTO> get(UUID identifier, @Nullable UUID source) {
        return toResponse(this.evidenceService.get(identifier, source));
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<?> content(UUID identifier, @Nullable String range, @Nullable UUID source) {
        return switch (this.evidenceService.content(identifier, range, source)) {
            case EvidenceDTO.Download download -> file(download);
            case EvidenceDTO.OutOfRange outOfRange -> HttpResponse.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header("Content-Range", "bytes */" + outOfRange.size());
            case EvidenceDTO.Error error -> HttpResponse.status(error.kind().status()).body(error);
            default -> HttpResponse.serverError();
        };
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<EvidenceDTO> delete(UUID identifier, @Nullable String reason, @Nullable String note, @Nullable UUID source) {
        return toResponse(this.evidenceService.delete(identifier, reason, note, source));
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<EvidenceDTO> deleteByOwner(String owner, @Nullable String reason, @Nullable String note, @Nullable UUID source) {
        return toResponse(this.evidenceService.deleteByOwner(owner, reason, note, source));
    }

    /**
     * The file as the browser may safely show it: the detected type, no sniffing, a sandboxing CSP and
     * no caching; only images and videos are shown inline, anything else would be a download.
     */
    private static HttpResponse<?> file(EvidenceDTO.Download download) {
        boolean inline = download.contentType().startsWith("image/") || download.contentType().startsWith("video/");
        StreamedFile body = new StreamedFile(download.stream(), MediaType.of(download.contentType()));
        MutableHttpResponse<StreamedFile> response = HttpResponse.<StreamedFile>status(download.partial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
                .body(body)
                .header("Content-Length", Long.toString(download.length()))
                .header("Accept-Ranges", "bytes")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox")
                .header("Cache-Control", "private, no-store")
                .header("Content-Disposition", (inline ? "inline" : "attachment") + "; filename=\"" + download.fileName() + "\"");
        return download.partial()
                ? response.header("Content-Range", "bytes " + download.start() + "-" + download.end() + "/" + download.total())
                : response;
    }

    private static HttpResponse<EvidenceDTO> toResponse(EvidenceDTO result) {
        if (result instanceof EvidenceDTO.Error error) {
            return HttpResponse.<EvidenceDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
