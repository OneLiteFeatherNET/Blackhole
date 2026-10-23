package net.onelitefeather.blackhole.backend.security;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("integration")
class TenantRestrictionIntegrationTest extends ApiIntegrationTest {

    @Override
    protected Map<String, Object> extraProperties() {
        return Map.of("blackhole.security.tenant-id", "tenant-a");
    }

    @Test
    void aTokenOfTheConfiguredTenantIsAccepted() {
        String token = this.jwt.delegated().claim("tid", "tenant-a").groups(TestJwtIssuer.VIEWER_GROUP).sign();

        assertEquals(200, get("/section", token).status(), "token from tenant-a");
    }

    @Test
    void aTokenOfAnotherTenantIsUnauthorized() {
        String token = this.jwt.delegated().claim("tid", "tenant-b").groups(TestJwtIssuer.VIEWER_GROUP).sign();

        assertEquals(401, get("/section", token).status(), "token from tenant-b");
    }

    @Test
    void aTokenWithoutATenantClaimIsUnauthorized() {
        String token = this.jwt.delegated().groups(TestJwtIssuer.VIEWER_GROUP).sign();

        assertEquals(401, get("/section", token).status(), "no tid claim");
    }
}
