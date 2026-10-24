package net.onelitefeather.blackhole.backend.casetimeline.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseConversationDTO;
import net.onelitefeather.blackhole.backend.casetimeline.service.CaseConversationService;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Roles;

import java.util.UUID;

/**
 * Routing and OpenAPI annotations live on {@link CaseConversationApi}. Database work blocks, so every
 * method runs on the blocking executor. The proxy has no rule here and is denied. Writes need
 * {@code MODERATOR}; redaction, erasure, reissue and export need {@code ADMIN}.
 */
@Version(ApiVersion.V1)
@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/case")
public class CaseConversationController implements CaseConversationApi {

    private final CaseConversationService conversation;

    @Inject
    public CaseConversationController(CaseConversationService conversation) {
        this.conversation = conversation;
    }

    @Override
    @Secured(Roles.MODERATOR)
    public HttpResponse<CaseConversationDTO> postMessage(String reference, CaseConversationDTO.MessageRequest request, @Nullable String idempotencyKey, Actor actor) {
        return toResponse(this.conversation.postMessage(reference, request, actor, idempotencyKey));
    }

    @Override
    @Secured(Roles.MODERATOR)
    public HttpResponse<CaseConversationDTO> editMessage(String reference, UUID messageId, CaseConversationDTO.MessageRequest request, Actor actor) {
        return toResponse(this.conversation.editMessage(reference, messageId, request, actor));
    }

    @Override
    @Secured(Roles.MODERATOR)
    public HttpResponse<CaseConversationDTO> deleteMessage(String reference, UUID messageId, Actor actor) {
        return toResponse(this.conversation.deleteMessage(reference, messageId, actor));
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<CaseConversationDTO> redactMessage(String reference, UUID messageId, CaseConversationDTO.RedactRequest request, Actor actor) {
        return toResponse(this.conversation.redactMessage(reference, messageId, request, actor));
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<CaseConversationDTO> erasePlayerMessages(String reference, CaseConversationDTO.RedactRequest request, Actor actor) {
        return toResponse(this.conversation.erasePlayerMessages(reference, request, actor));
    }

    @Override
    @Secured(Roles.MODERATOR)
    public HttpResponse<CaseConversationDTO> markBanIdLeaked(String reference, CaseConversationDTO.LeakRequest request, Actor actor) {
        return toResponse(this.conversation.markBanIdLeaked(reference, request, actor));
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<CaseConversationDTO> reissueBanId(String reference, CaseConversationDTO.ReissueRequest request, Actor actor) {
        return toResponse(this.conversation.reissueBanId(reference, request, actor));
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<?> exportTranscript(String reference, @Nullable String format, @Nullable Boolean includeNotes, Actor actor) {
        CaseConversationDTO result = this.conversation.exportTranscript(reference, format, Boolean.TRUE.equals(includeNotes), actor);
        if (result instanceof CaseConversationDTO.Transcript transcript) {
            return HttpResponse.ok(transcript.content())
                    .contentType(transcript.contentType())
                    .header("Cache-Control", "no-store")
                    .header("Content-Disposition", "attachment; filename=\"" + transcript.fileName() + "\"");
        }
        return toResponse(result);
    }

    private static HttpResponse<CaseConversationDTO> toResponse(CaseConversationDTO result) {
        if (result instanceof CaseConversationDTO.Error error) {
            return HttpResponse.<CaseConversationDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
