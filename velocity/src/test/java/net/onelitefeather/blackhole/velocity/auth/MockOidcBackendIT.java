package net.onelitefeather.blackhole.velocity.auth;

import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.model.PunishmentApplyResponse;
import net.onelitefeather.blackhole.velocity.config.AuthConfig;
import net.onelitefeather.blackhole.velocity.config.BlackholeApiClients;
import net.onelitefeather.blackhole.velocity.punish.HttpBackendGateway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Manual check against a running backend and the mock OIDC server of {@code docker/docker-compose.yml}
 * (task 3.6 of {@code add-team-sso}). Skipped unless {@code BLACKHOLE_IT_BACKEND_URL} and
 * {@code BLACKHOLE_IT_TEMPLATE} are set, so it never runs in the normal build. It prints the owner and the staff UUID it used; check the row in the
 * database ({@code recorded_by_oid} must be the proxy's {@code oid}).
 *
 * <pre>
 * BLACKHOLE_IT_BACKEND_URL=http://localhost:18081 BLACKHOLE_IT_TEMPLATE=spam ./gradlew :velocity:test --tests '*MockOidcBackendIT'
 * </pre>
 */
@Tag("integration")
class MockOidcBackendIT {

    @Test
    void theProxyGetsAProxyTokenFromTheMockAndAppliesAPunishmentOnBehalfOfAStaffMember() throws Exception {
        String backend = System.getenv("BLACKHOLE_IT_BACKEND_URL");
        assumeTrue(backend != null && !backend.isBlank(), "BLACKHOLE_IT_BACKEND_URL is not set");

        AuthConfig auth = new AuthConfig().withEnvironment(Map.of(
                AuthConfig.ENV_CLIENT_ID, "blackhole-proxy",
                AuthConfig.ENV_CLIENT_SECRET, "mock-secret",
                AuthConfig.ENV_SCOPE, "api://blackhole-api/.default",
                AuthConfig.ENV_TOKEN_URL, "http://localhost:18090/blackhole/token")::get);
        ClientCredentialsTokenProvider tokens = new ClientCredentialsTokenProvider(auth, new JdkTokenTransport(), Clock.systemUTC());
        ApiClient client = BlackholeApiClients.create(backend, tokens);

        String template = System.getenv("BLACKHOLE_IT_TEMPLATE");
        assumeTrue(template != null && !template.isBlank(), "BLACKHOLE_IT_TEMPLATE (an existing template key) is not set");

        UUID staff = UUID.randomUUID();
        String owner = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-512").digest(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)));
        PunishmentApplyResponse response = new HttpBackendGateway(client, Clock.systemUTC())
                .apply(owner, template, staff, null, false);

        assertNotNull(response.getPunishment(), "the punishment was applied");
        System.out.println("IT_RESULT owner=" + owner + " staff=" + staff + " template=" + template
                + " punishment=" + response.getPunishment().getIdentifier());
    }
}
