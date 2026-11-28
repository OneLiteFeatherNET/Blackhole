package net.onelitefeather.blackhole.backend.discord.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.multipart.StreamingFileUpload;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.discord.attachment.DiscordAttachmentService;
import net.onelitefeather.blackhole.backend.discord.dto.DiscordTicketDTO;
import net.onelitefeather.blackhole.backend.discord.ingest.DiscordIngestService;
import net.onelitefeather.blackhole.backend.discord.ticket.TicketService;
import net.onelitefeather.blackhole.backend.discord.ticket.TicketState;
import net.onelitefeather.blackhole.backend.evidence.controller.SafeFileResponse;
import net.onelitefeather.blackhole.backend.problem.ProblemMapper;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Roles;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Part of the {@code /discord/**} facade: the role is {@code DISCORD} only, staff and the proxy are denied. Routing and OpenAPI annotations
 * live on {@link DiscordTicketApi}. Database work blocks, so every method runs on the blocking executor.
 */
@Version(ApiVersion.V1)
@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/discord/tickets")
public class DiscordTicketController implements DiscordTicketApi {

    private final TicketService tickets;
    private final DiscordIngestService ingest;
    private final DiscordAttachmentService attachments;

    @Inject
    public DiscordTicketController(TicketService tickets, DiscordIngestService ingest, DiscordAttachmentService attachments) {
        this.tickets = tickets;
        this.ingest = ingest;
        this.attachments = attachments;
    }

    @Override
    @Secured(Roles.DISCORD)
    public HttpResponse<DiscordTicketDTO> uploadAttachment(UUID id, @Nullable StreamingFileUpload file, String discordAttachmentId,
                                                           String authorDiscordUserId, Actor actor) {
        if (file == null) {
            return respond(DiscordTicketDTO.Error.of("a file is required", ErrorKind.VALIDATION));
        }
        try (InputStream content = file.asInputStream()) {
            return respond(this.attachments.upload(id, file.getFilename(), discordAttachmentId, authorDiscordUserId, content, actor));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    @Secured(Roles.DISCORD)
    public HttpResponse<?> attachment(UUID id, UUID attachmentId) {
        DiscordAttachmentService.Reply reply = this.attachments.download(id, attachmentId);
        return switch (reply) {
            case DiscordAttachmentService.Reply.Content content -> SafeFileResponse.of(content.download(), false);
            case DiscordAttachmentService.Reply.Failure failure -> problem(failure.error());
        };
    }

    @Override
    @Secured(Roles.DISCORD)
    public HttpResponse<DiscordTicketDTO> open(DiscordTicketDTO.OpenRequest request, Actor actor) {
        return respond(this.tickets.open(request, actor));
    }

    @Override
    @Secured(Roles.DISCORD)
    public HttpResponse<DiscordTicketDTO> list(@Nullable List<TicketState> state) {
        return respond(this.tickets.list(state));
    }

    @Override
    @Secured(Roles.DISCORD)
    public HttpResponse<DiscordTicketDTO> get(UUID id) {
        return respond(this.tickets.get(id));
    }

    @Override
    @Secured(Roles.DISCORD)
    public HttpResponse<DiscordTicketDTO> patch(UUID id, DiscordTicketDTO.PatchRequest request, Actor actor) {
        return respond(this.tickets.patch(id, request, actor));
    }

    @Override
    @Secured(Roles.DISCORD)
    public HttpResponse<DiscordTicketDTO> outbox(UUID id, @Nullable Long afterSeq, @Nullable Integer limit) {
        return respond(this.tickets.outbox(id, afterSeq, limit));
    }

    @Override
    @Secured(Roles.DISCORD)
    public HttpResponse<DiscordTicketDTO> ingest(UUID id, DiscordTicketDTO.IngestRequest request, Actor actor) {
        return respond(this.ingest.ingest(id, request, actor));
    }

    @Override
    @Secured(Roles.DISCORD)
    public HttpResponse<DiscordTicketDTO> link(UUID id, DiscordTicketDTO.LinkRequest request) {
        return respond(this.tickets.link(id, request));
    }

    @Override
    @Secured(Roles.DISCORD)
    public HttpResponse<DiscordTicketDTO> close(UUID id, DiscordTicketDTO.CloseRequest request, Actor actor) {
        return respond(this.tickets.close(id, request, actor));
    }

    @Override
    @Secured(Roles.DISCORD)
    public HttpResponse<DiscordTicketDTO> ensureProtocol(UUID id) {
        return respond(this.tickets.ensureProtocol(id));
    }

    private static HttpResponse<DiscordTicketDTO> respond(DiscordTicketDTO result) {
        if (result instanceof DiscordTicketDTO.Error error) {
            return problem(error);
        }
        return HttpResponse.ok(result);
    }

    /** A ticket failure as a problem: the code is the machine code, and the data the bot acts on stays beside it. */
    private static <T> HttpResponse<T> problem(DiscordTicketDTO.Error error) {
        Map<String, Object> extensions = new HashMap<>();
        extensions.put("availableFrom", error.availableFrom());
        extensions.put("backendCount", error.backendCount());
        extensions.put("backendLastMessageId", error.backendLastMessageId());
        String code = error.code() == null ? null : error.code().name();
        return ProblemMapper.fromError(error, error.kind().status(), code, ProblemMapper.instance(), extensions).response();
    }
}
