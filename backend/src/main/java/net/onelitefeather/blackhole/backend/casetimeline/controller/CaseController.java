package net.onelitefeather.blackhole.backend.casetimeline.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.casetimeline.CasePriority;
import net.onelitefeather.blackhole.backend.casetimeline.CaseStatus;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseDTO;
import net.onelitefeather.blackhole.backend.casetimeline.service.CaseService;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Roles;

import java.util.List;
import java.util.UUID;

/**
 * Routing and OpenAPI annotations live on {@link CaseApi}, which this class implements. Database
 * work blocks, so every method runs on the blocking executor. The proxy has no rule here and is denied.
 */
@Version(ApiVersion.V1)
@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/case")
public class CaseController implements CaseApi {

    private final CaseService caseService;

    @Inject
    public CaseController(CaseService caseService) {
        this.caseService = caseService;
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<CaseDTO> list(
            @Nullable CaseStatus status, @Nullable UUID assignee, @Nullable String tag, @Nullable CasePriority priority,
            @Nullable String section, @Nullable String punishmentState, @Nullable Integer page, @Nullable Integer size
    ) {
        return toResponse(this.caseService.list(status, assignee, tag, priority, section, punishmentState, page, size));
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<CaseDTO> get(String reference) {
        return toResponse(this.caseService.get(reference));
    }

    @Override
    @Secured(Roles.VIEWER)
    public HttpResponse<CaseDTO> timeline(
            String reference, @Nullable String cursor, @Nullable Integer size, @Nullable List<String> type, @Nullable String actorTrust,
            @Nullable Long from, @Nullable Long to, @Nullable String order
    ) {
        return toResponse(this.caseService.timeline(reference, cursor, size, type, actorTrust, from, to, order));
    }

    @Override
    @Secured(Roles.MODERATOR)
    public HttpResponse<CaseDTO> update(String reference, CaseDTO.UpdateRequest request, Actor actor) {
        return toResponse(this.caseService.update(reference, request, actor));
    }

    @Override
    @Secured(Roles.MODERATOR)
    public HttpResponse<CaseDTO> addNote(String reference, CaseDTO.NoteRequest request, Actor actor) {
        return toResponse(this.caseService.addNote(reference, request, actor));
    }

    @Override
    @Secured(Roles.MODERATOR)
    public HttpResponse<CaseDTO> editNote(String reference, UUID noteId, CaseDTO.NoteRequest request, Actor actor) {
        return toResponse(this.caseService.editNote(reference, noteId, request, actor));
    }

    @Override
    @Secured(Roles.ADMIN)
    public HttpResponse<CaseDTO> redactNote(String reference, UUID noteId, CaseDTO.RedactRequest request, Actor actor) {
        return toResponse(this.caseService.redactNote(reference, noteId, request, actor));
    }

    private static HttpResponse<CaseDTO> toResponse(CaseDTO result) {
        if (result instanceof CaseDTO.Error error) {
            return HttpResponse.<CaseDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
