package net.onelitefeather.blackhole.backend.controller;

import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.core.version.annotation.Version;
import io.micronaut.security.annotation.Secured;
import io.micronaut.validation.Validated;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.database.entities.ConnectorRegistrationEntity;
import net.onelitefeather.blackhole.backend.database.entities.EventSubscriptionEntity;
import net.onelitefeather.blackhole.backend.database.repository.ConnectorRegistrationRepository;
import net.onelitefeather.blackhole.backend.database.repository.EventSubscriptionRepository;
import net.onelitefeather.blackhole.backend.dto.ConnectorRegistrationCreatedDTO;
import net.onelitefeather.blackhole.backend.dto.ConnectorRegistrationDTO;
import net.onelitefeather.blackhole.backend.dto.ConnectorRegistrationRequestDTO;
import net.onelitefeather.blackhole.backend.dto.ConnectorStatus;
import net.onelitefeather.blackhole.backend.dto.EventSubscriptionCreatedDTO;
import net.onelitefeather.blackhole.backend.dto.EventSubscriptionDTO;
import net.onelitefeather.blackhole.backend.dto.EventSubscriptionRequestDTO;
import net.onelitefeather.blackhole.backend.events.InvalidWebhookUrlException;
import net.onelitefeather.blackhole.backend.events.WebhookUrlValidator;
import net.onelitefeather.blackhole.backend.security.ConnectorScopes;
import net.onelitefeather.blackhole.backend.security.Roles;
import net.onelitefeather.blackhole.backend.utils.SecretHasher;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.UUID;

/**
 * Admin-only CRUD for connector registrations and their event subscriptions. Client/signing
 * secrets are generated server-side and shown exactly once, at creation time - only their
 * SHA-512 hash (for the client secret) or plaintext (for the signing secret, needed to compute
 * outbound HMAC signatures) is persisted.
 */
@Secured({Roles.ADMIN})
@Version(ApiVersion.V1)
@Controller("/connector")
public class ConnectorController {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ConnectorRegistrationRepository connectorRepository;
    private final EventSubscriptionRepository subscriptionRepository;
    private final WebhookUrlValidator webhookUrlValidator;

    @Inject
    public ConnectorController(
            ConnectorRegistrationRepository connectorRepository,
            EventSubscriptionRepository subscriptionRepository,
            WebhookUrlValidator webhookUrlValidator
    ) {
        this.connectorRepository = connectorRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.webhookUrlValidator = webhookUrlValidator;
    }

    private static String generateSecret() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Operation(summary = "Register a connector", operationId = "registerConnector", tags = {"Connector"})
    @Validated
    @Post("/")
    public HttpResponse<ConnectorRegistrationCreatedDTO> register(@Body @Valid ConnectorRegistrationRequestDTO request) {
        if (!ConnectorScopes.ALL.containsAll(request.scopes())) {
            return HttpResponse.badRequest();
        }

        String clientId = UUID.randomUUID().toString();
        String clientSecret = generateSecret();

        ConnectorRegistrationEntity entity = new ConnectorRegistrationEntity(
                request.name(),
                clientId,
                SecretHasher.hash(clientSecret),
                request.scopes(),
                ConnectorStatus.ACTIVE,
                new HashMap<>()
        );
        ConnectorRegistrationEntity saved = this.connectorRepository.save(entity);

        return HttpResponse.ok(new ConnectorRegistrationCreatedDTO(saved.toDTO(), clientSecret));
    }

    @Operation(summary = "Get all connectors", operationId = "getConnectors", tags = {"Connector"})
    @Get("/")
    public HttpResponse<Page<ConnectorRegistrationDTO>> getAll(Pageable pageable) {
        Page<ConnectorRegistrationEntity> entities = this.connectorRepository.findAll(pageable);
        return HttpResponse.ok(entities.map(ConnectorRegistrationEntity::toDTO));
    }

    @Operation(summary = "Get a connector by ID", operationId = "getConnectorById", tags = {"Connector"})
    @Get("/{identifier}")
    public HttpResponse<ConnectorRegistrationDTO> get(UUID identifier) {
        ConnectorRegistrationEntity entity = this.connectorRepository.findById(identifier).orElse(null);
        if (entity == null) {
            return HttpResponse.notFound();
        }
        return HttpResponse.ok(entity.toDTO());
    }

    @Operation(summary = "Update a connector's name/scopes/status", operationId = "updateConnector", tags = {"Connector"})
    @Validated
    @Post("/{identifier}/update")
    public HttpResponse<ConnectorRegistrationDTO> update(UUID identifier, @Body @Valid ConnectorRegistrationDTO update) {
        ConnectorRegistrationEntity entity = this.connectorRepository.findById(identifier).orElse(null);
        if (entity == null) {
            return HttpResponse.notFound();
        }
        if (!ConnectorScopes.ALL.containsAll(update.scopes())) {
            return HttpResponse.badRequest();
        }

        entity.setName(update.name());
        entity.setScopes(update.scopes());
        entity.setStatus(update.status());
        ConnectorRegistrationEntity saved = this.connectorRepository.update(entity);
        return HttpResponse.ok(saved.toDTO());
    }

    @Operation(summary = "Delete a connector", operationId = "removeConnector", tags = {"Connector"})
    @Delete("/{identifier}")
    public HttpResponse<ConnectorRegistrationDTO> remove(UUID identifier) {
        ConnectorRegistrationEntity entity = this.connectorRepository.findById(identifier).orElse(null);
        if (entity == null) {
            return HttpResponse.notFound();
        }
        this.connectorRepository.delete(entity);
        return HttpResponse.ok(entity.toDTO());
    }

    @Operation(summary = "Create an event subscription for a connector", operationId = "addEventSubscription", tags = {"Connector"})
    @ApiResponse(responseCode = "400", description = "deliveryUrl failed SSRF validation (see message)")
    @Validated
    @Post("/subscription")
    public HttpResponse<?> addSubscription(@Body @Valid EventSubscriptionRequestDTO request) {
        ConnectorRegistrationEntity connector = this.connectorRepository.findById(request.connectorId()).orElse(null);
        if (connector == null) {
            return HttpResponse.notFound();
        }

        try {
            this.webhookUrlValidator.validate(request.deliveryUrl());
        } catch (InvalidWebhookUrlException e) {
            return HttpResponse.badRequest(e.getMessage());
        }

        String signingSecret = generateSecret();
        EventSubscriptionEntity entity = new EventSubscriptionEntity(
                connector, request.eventTypes(), request.deliveryUrl(), signingSecret, true, new HashMap<>()
        );
        EventSubscriptionEntity saved = this.subscriptionRepository.save(entity);

        return HttpResponse.ok(new EventSubscriptionCreatedDTO(saved.toDTO(), signingSecret));
    }

    @Operation(summary = "Get all event subscriptions for a connector", operationId = "getEventSubscriptions", tags = {"Connector"})
    @Get("/{connectorId}/subscription")
    public HttpResponse<Page<EventSubscriptionDTO>> getSubscriptions(UUID connectorId, Pageable pageable) {
        ConnectorRegistrationEntity connector = this.connectorRepository.findById(connectorId).orElse(null);
        if (connector == null) {
            return HttpResponse.notFound();
        }
        Page<EventSubscriptionEntity> entities = this.subscriptionRepository.findByConnectorIdentifier(connectorId, pageable);
        return HttpResponse.ok(entities.map(EventSubscriptionEntity::toDTO));
    }

    @Operation(summary = "Delete an event subscription", operationId = "removeEventSubscription", tags = {"Connector"})
    @Delete("/subscription/{identifier}")
    public HttpResponse<EventSubscriptionDTO> removeSubscription(UUID identifier) {
        EventSubscriptionEntity entity = this.subscriptionRepository.findById(identifier).orElse(null);
        if (entity == null) {
            return HttpResponse.notFound();
        }
        this.subscriptionRepository.delete(entity);
        return HttpResponse.ok(entity.toDTO());
    }
}
