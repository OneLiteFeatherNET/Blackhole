package net.onelitefeather.blackhole.backend.publicapi.controller;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.version.annotation.Version;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import jakarta.inject.Inject;
import net.onelitefeather.blackhole.backend.controller.ApiVersion;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicAppealDTO;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicConversationDTO;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicError;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicErrorBody;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicProtocolDTO;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicPunishmentDTO;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicSessionDTO;
import net.onelitefeather.blackhole.backend.publicapi.service.PlayerSessionService;
import net.onelitefeather.blackhole.backend.publicapi.service.PublicAppealService;
import net.onelitefeather.blackhole.backend.publicapi.service.PublicConversationService;
import net.onelitefeather.blackhole.backend.publicapi.service.PublicProtocolService;
import net.onelitefeather.blackhole.backend.publicapi.service.PublicPunishmentService;
import net.onelitefeather.blackhole.backend.security.PlayerSession;
import net.onelitefeather.blackhole.backend.security.PlayerSessionRule;

/**
 * Routing and OpenAPI annotations live on {@link PublicApi}. The login is anonymous; every other route needs a
 * valid player session ({@link PlayerSessionRule}), which no staff or proxy token carries. Database work blocks,
 * so every method runs on the blocking executor. All answers are {@code no-store}.
 */
@Version(ApiVersion.V1)
@ExecuteOn(TaskExecutors.BLOCKING)
@Controller("/public")
public class PublicController implements PublicApi {

    private final PlayerSessionService sessions;
    private final PublicPunishmentService punishments;
    private final PublicAppealService appeals;
    private final PublicConversationService conversation;
    private final PublicProtocolService protocol;

    @Inject
    public PublicController(
            PlayerSessionService sessions, PublicPunishmentService punishments, PublicAppealService appeals, PublicConversationService conversation,
            PublicProtocolService protocol
    ) {
        this.protocol = protocol;
        this.sessions = sessions;
        this.punishments = punishments;
        this.appeals = appeals;
        this.conversation = conversation;
    }

    @Override
    @Secured(SecurityRule.IS_ANONYMOUS)
    public HttpResponse<?> login(@Nullable PublicSessionDTO.LoginRequest request) {
        return respond(this.sessions.login(request == null ? null : request.banId()));
    }

    @Override
    @Secured(PlayerSessionRule.ROLE)
    public HttpResponse<?> session(PlayerSession session) {
        return respond(this.sessions.current(session));
    }

    @Override
    @Secured(PlayerSessionRule.ROLE)
    public HttpResponse<?> logout(PlayerSession session) {
        this.sessions.logout(session);
        return HttpResponse.noContent().header("Cache-Control", "no-store");
    }

    @Override
    @Secured(PlayerSessionRule.ROLE)
    public HttpResponse<?> punishment(PlayerSession session) {
        return respond(this.punishments.facts(session));
    }

    @Override
    @Secured(PlayerSessionRule.ROLE)
    public HttpResponse<?> fileAppeal(PlayerSession session, @Nullable PublicAppealDTO.FileRequest request) {
        return respond(this.appeals.file(session, request));
    }

    @Override
    @Secured(PlayerSessionRule.ROLE)
    public HttpResponse<?> appealConditions(PlayerSession session) {
        return respond(this.appeals.conditions());
    }

    @Override
    @Secured(PlayerSessionRule.ROLE)
    public HttpResponse<?> appeal(PlayerSession session) {
        return respond(this.appeals.read(session));
    }

    @Override
    @Secured(PlayerSessionRule.ROLE)
    public HttpResponse<?> conversation(PlayerSession session, @Nullable Long cursor) {
        return respond(this.conversation.read(session, cursor));
    }

    @Override
    @Secured(PlayerSessionRule.ROLE)
    public HttpResponse<?> postMessage(PlayerSession session, @Nullable PublicConversationDTO.PostRequest request, @Nullable String idempotencyKey) {
        return respond(this.conversation.post(session, request, idempotencyKey));
    }

    @Override
    @Secured(PlayerSessionRule.ROLE)
    public HttpResponse<?> appealProtocol(PlayerSession session, @Nullable String format) {
        PublicProtocolDTO result = this.protocol.read(session, format);
        if (result instanceof PublicProtocolDTO.Download download) {
            return noStore(HttpResponse.ok(download.content())
                    .contentType(download.contentType())
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Content-Security-Policy", "sandbox")
                    .header("Content-Disposition", "attachment; filename=\"" + download.fileName() + "\""));
        }
        return respond(result);
    }

    private static HttpResponse<?> respond(Object result) {
        if (result instanceof PublicError error) {
            return noStore(HttpResponse.status(error.kind().status()).body(PublicErrorBody.of(error)));
        }
        return noStore(HttpResponse.ok(result));
    }

    private static <T> HttpResponse<T> noStore(io.micronaut.http.MutableHttpResponse<T> response) {
        return response.header("Cache-Control", "no-store");
    }
}
