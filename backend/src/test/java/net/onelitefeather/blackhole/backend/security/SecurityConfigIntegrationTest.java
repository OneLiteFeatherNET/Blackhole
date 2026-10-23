package net.onelitefeather.blackhole.backend.security;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The {@code blackhole.security} block of {@code application.yml} binds, with its dev defaults and comma separated overrides. */
@Tag("integration")
class SecurityConfigIntegrationTest extends ApiIntegrationTest {

    @Override
    protected Map<String, Object> extraProperties() {
        return Map.of("blackhole.security.roles.viewer", "group-a,group-b");
    }

    @Test
    void theDevDefaultsBindToTheMockServerGroupIds() {
        RoleMappingProperties roles = this.context.getBean(RoleMappingProperties.class);

        assertEquals("groups", roles.claimHuman(), "human claim");
        assertEquals("roles", roles.claimMachine(), "machine claim");
        assertEquals(List.of(TestJwtIssuer.ADMIN_GROUP), roles.admin(), "admin group");
        assertEquals(List.of(TestJwtIssuer.MODERATOR_GROUP), roles.moderator(), "moderator group");
        assertEquals(List.of(TestJwtIssuer.METRICS_GROUP), roles.metrics(), "metrics group");
        assertEquals(List.of(TestJwtIssuer.PROXY_ROLE), roles.machineProxy(), "proxy app role");
        assertEquals(List.of(TestJwtIssuer.METRICS_ROLE), roles.machineMetrics(), "metrics app role");
    }

    @Test
    void severalValuesCanBeGivenCommaSeparated() {
        assertEquals(List.of("group-a", "group-b"), this.context.getBean(RoleMappingProperties.class).viewer(), "viewer groups");
    }

    @Test
    void theSecuritySettingsDefaultToTheLocalSetup() {
        SecurityProperties security = this.context.getBean(SecurityProperties.class);

        assertEquals("", security.tenantId(), "no tenant restriction by default");
        assertEquals(false, security.allowInsecureIssuer(), "the insecure issuer override is off");
        assertEquals("http://localhost:18090/blackhole/jwks", security.jwksUrl(), "mock server keys");
    }
}
