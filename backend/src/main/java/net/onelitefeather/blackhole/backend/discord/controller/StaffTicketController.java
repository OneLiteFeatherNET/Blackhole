package net.onelitefeather.blackhole.backend.discord.controller;

import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.security.annotation.Secured;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.discord.dto.StaffTicketDTO;
import net.onelitefeather.blackhole.backend.discord.ticket.StaffTicketService;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Roles;

/** The staff lock of an appeal ticket, under the case routes: the panel (moderators) and the Discord bot (for a named staff member). */
@Version(ApiVersion.V1)
@Controller("/case")
public class StaffTicketController implements StaffTicketApi {

    private final StaffTicketService service;

    @Inject
    public StaffTicketController(StaffTicketService service) {
        this.service = service;
    }

    @Override
    @Secured({Roles.MODERATOR, Roles.ADMIN, Roles.DISCORD})
    public HttpResponse<StaffTicketDTO> freeze(String reference, StaffTicketDTO.FreezeRequest request, Actor actor) {
        StaffTicketDTO result = this.service.freeze(reference, request, actor);
        if (result instanceof StaffTicketDTO.Error error) {
            return HttpResponse.<StaffTicketDTO>status(error.kind().status()).body(error);
        }
        return HttpResponse.ok(result);
    }
}
