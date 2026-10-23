package net.onelitefeather.blackhole.backend.security;

import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** With {@code blackhole.docs.enabled} the Swagger views are served, but only to a VIEWER. */
@Tag("integration")
class DocsEnabledIntegrationTest extends ApiIntegrationTest {

    /** The generated spec, named after the OpenAPI version of BlackholeApplication: bump it with that. */
    private static final String PAGE = "/swagger/blackhole-api-0.0.23.yml";

    @Override
    protected Map<String, Object> extraProperties() {
        return Map.of("blackhole.docs.enabled", true);
    }

    @Test
    void anAnonymousCallerCannotOpenTheDocumentation() {
        assertEquals(401, get(PAGE, null).status(), "no token");
    }

    @Test
    void aCallerWithoutARoleCannotOpenTheDocumentation() {
        assertEquals(403, get(PAGE, this.jwt.noGroup()).status(), "token without role");
    }

    @Test
    void aViewerCanOpenTheDocumentation() {
        assertEquals(200, get(PAGE, this.jwt.viewer()).status(), "viewer");
    }
}
