package net.onelitefeather.blackhole.backend.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.core.version.annotation.Version;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.token.jwt.generator.JwtTokenGenerator;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.backend.database.entities.ConnectorRegistrationEntity;
import net.onelitefeather.blackhole.backend.database.repository.ConnectorRegistrationRepository;
import net.onelitefeather.blackhole.backend.dto.ConnectorStatus;
import net.onelitefeather.blackhole.backend.dto.ConnectorTokenRequestDTO;
import net.onelitefeather.blackhole.backend.dto.TokenResponseDTO;
import net.onelitefeather.blackhole.backend.utils.SecretHasher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OAuth2 client-credentials style token exchange for connectors. Issued tokens carry the
 * connector's granted scopes directly as JWT roles (plus a {@code CONNECTOR} marker role), so
 * existing {@code @Secured} checks on read endpoints can reference a scope string exactly like
 * any other role - no separate scope-checking infrastructure needed.
 */
@Version(ApiVersion.V1)
@Controller("/connector/oauth2")
public class ConnectorAuthController {

    private final ConnectorRegistrationRepository connectorRepository;
    private final JwtTokenGenerator tokenGenerator;

    @Inject
    public ConnectorAuthController(ConnectorRegistrationRepository connectorRepository, JwtTokenGenerator tokenGenerator) {
        this.connectorRepository = connectorRepository;
        this.tokenGenerator = tokenGenerator;
    }

    @Operation(
            summary = "Exchange connector client credentials for a token",
            description = "OAuth2 client-credentials style exchange. Returns a token scoped to the connector's granted scopes.",
            operationId = "connectorToken",
            tags = {"Connector"}
    )
    @Secured(SecurityRule.IS_ANONYMOUS)
    @Post("/token")
    public HttpResponse<?> token(@Valid @Body ConnectorTokenRequestDTO request) {
        ConnectorRegistrationEntity connector = this.connectorRepository.findByOauth2ClientId(request.clientId()).orElse(null);
        if (connector == null || connector.getStatus() != ConnectorStatus.ACTIVE) {
            return HttpResponse.unauthorized();
        }

        String suppliedHash = SecretHasher.hash(request.clientSecret());
        if (!MessageDigest.isEqual(
                suppliedHash.getBytes(StandardCharsets.UTF_8),
                connector.getOauth2ClientSecretHash().getBytes(StandardCharsets.UTF_8)
        )) {
            return HttpResponse.unauthorized();
        }

        List<String> roles = new ArrayList<>(connector.getScopes());
        roles.add("CONNECTOR");

        Authentication authentication = Authentication.build(
                "connector:" + connector.getIdentifier(),
                roles,
                Map.of("connectorId", connector.getIdentifier().toString())
        );
        Optional<String> token = this.tokenGenerator.generateToken(authentication, null);
        if (token.isEmpty()) {
            return HttpResponse.serverError("Failed to generate token");
        }
        return HttpResponse.ok(new TokenResponseDTO(token.get()));
    }
}
