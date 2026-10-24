package net.onelitefeather.blackhole.backend.evidence.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.multipart.StreamingFileUpload;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceDTO;
import net.onelitefeather.blackhole.backend.evidence.service.ChatFileService;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Roles;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Routing and OpenAPI annotations live on {@link ChatAttachmentApi}. File work blocks, so the method runs on the blocking executor.
 * {@code MODERATOR} only: the proxy, viewers and the Discord bot have no rule here and are denied.
 */
@Version(ApiVersion.V1)
@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/case")
public class ChatAttachmentController implements ChatAttachmentApi {

    private final ChatFileService files;

    @Inject
    public ChatAttachmentController(ChatFileService files) {
        this.files = files;
    }

    @Override
    @Secured(Roles.MODERATOR)
    public HttpResponse<EvidenceDTO> upload(String reference, StreamingFileUpload file, Actor actor) {
        try (InputStream content = file.asInputStream()) {
            EvidenceDTO result = this.files.stageForCase(reference, file.getFilename(), content, actor);
            if (result instanceof EvidenceDTO.Error error) {
                return HttpResponse.<EvidenceDTO>status(error.kind().status()).body(error);
            }
            return HttpResponse.ok(result);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
