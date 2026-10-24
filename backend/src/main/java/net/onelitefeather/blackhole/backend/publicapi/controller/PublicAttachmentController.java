package net.onelitefeather.blackhole.backend.publicapi.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.multipart.StreamingFileUpload;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.evidence.controller.SafeFileResponse;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicAttachmentDTO;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicErrorBody;
import net.onelitefeather.blackhole.backend.publicapi.service.PublicAttachmentService;
import net.onelitefeather.blackhole.backend.security.PlayerSession;
import net.onelitefeather.blackhole.backend.security.PlayerSessionRule;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Routing and OpenAPI annotations live on {@link PublicAttachmentApi}. Storage work blocks, so every method runs on the blocking executor.
 * Each method makes one service call and maps its answer to a response.
 */
@Version(ApiVersion.V1)
@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/public")
public class PublicAttachmentController implements PublicAttachmentApi {

    private final PublicAttachmentService attachments;

    @Inject
    public PublicAttachmentController(PublicAttachmentService attachments) {
        this.attachments = attachments;
    }

    @Override
    @Secured(PlayerSessionRule.ROLE)
    public HttpResponse<?> upload(PlayerSession session, @Nullable StreamingFileUpload file) {
        if (file == null) {
            return noStore(HttpResponse.badRequest(new PublicErrorBody("VALIDATION", null)));
        }
        try (InputStream content = file.asInputStream()) {
            return respond(this.attachments.stage(session, file.getFilename(), content));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    @Secured(PlayerSessionRule.ROLE)
    public HttpResponse<?> download(PlayerSession session, String id) {
        PublicAttachmentDTO result = this.attachments.download(session, id);
        if (result instanceof PublicAttachmentDTO.Content content) {
            return SafeFileResponse.of(content.download(), false);
        }
        return respond(result);
    }

    @Override
    @Secured(PlayerSessionRule.ROLE)
    public HttpResponse<?> delete(PlayerSession session, String id) {
        PublicAttachmentDTO result = this.attachments.unstage(session, id);
        if (result instanceof PublicAttachmentDTO.Deleted) {
            return noStore(HttpResponse.noContent());
        }
        return respond(result);
    }

    private static HttpResponse<?> respond(PublicAttachmentDTO result) {
        if (result instanceof PublicAttachmentDTO.Error error) {
            return noStore(HttpResponse.status(error.kind().status()).body(PublicErrorBody.of(error)));
        }
        return noStore(HttpResponse.ok(result));
    }

    private static <T> MutableHttpResponse<T> noStore(MutableHttpResponse<T> response) {
        return response.header("Cache-Control", "no-store");
    }

}
