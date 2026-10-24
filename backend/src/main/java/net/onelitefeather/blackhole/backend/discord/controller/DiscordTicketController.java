package net.onelitefeather.blackhole.backend.discord.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.discord.dto.DiscordTicketDTO;
import net.onelitefeather.blackhole.backend.discord.ingest.DiscordIngestService;
import net.onelitefeather.blackhole.backend.discord.ticket.TicketService;
import net.onelitefeather.blackhole.backend.discord.ticket.TicketState;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Roles;

import java.util.List;
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

    @Inject
    public DiscordTicketController(TicketService tickets, DiscordIngestService ingest) {
        this.tickets = tickets;
        this.ingest = ingest;
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
            return HttpResponse.<DiscordTicketDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
