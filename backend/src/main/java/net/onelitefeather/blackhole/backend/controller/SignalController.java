package net.onelitefeather.blackhole.backend.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.security.annotation.Secured;
import io.micronaut.validation.Validated;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.dto.SignalDTO;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.security.ConnectorScopes;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Generic ingestion point for external signals (e.g. anticheat flags). Blackhole doesn't
 * interpret {@code signalType} at all here - it's published as a domain event
 * ({@code signal.<type>}) and left for a future consumer (Phase 5's ELO system, or any
 * connector subscribed to it) to act on. This genericness is the point: a new anticheat
 * integration needs zero new backend code, just a connector registration and a chosen
 * {@code signalType}.
 */
@Secured(ConnectorScopes.SIGNAL_WRITE)
@Controller(ApiVersion.V1 + "/signal")
public class SignalController {

    private final DomainEventPublisher eventPublisher;

    @Inject
    public SignalController(DomainEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Operation(summary = "Submit an external signal", operationId = "submitSignal", tags = {"Signal"})
    @Validated
    @Post("/{tenantId}")
    public HttpResponse<?> submit(UUID tenantId, @Body @Valid SignalDTO signal) {
        Map<String, Object> payload = new HashMap<>(signal.payload());
        payload.put("tenantId", tenantId.toString());
        payload.put("owner", signal.owner());
        payload.put("signalType", signal.signalType());

        this.eventPublisher.publish("signal." + sanitize(signal.signalType()), payload);

        return HttpResponse.ok();
    }

    private static String sanitize(String signalType) {
        return signalType.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }
}
