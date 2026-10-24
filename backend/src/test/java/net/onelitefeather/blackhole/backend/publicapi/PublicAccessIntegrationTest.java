package net.onelitefeather.blackhole.backend.publicapi;

import io.micronaut.http.HttpMethod;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two directions of the public rule: no staff or proxy token opens a route that needs a player session,
 * and a player session opens nothing outside {@code /public}. Only 401 and 403 mean "refused by security".
 */
@Tag("integration")
class PublicAccessIntegrationTest extends PublicApiTestBase {

    private record Route(HttpMethod method, String path, Object body) {
    }

    private static final List<Route> SESSION_ROUTES = List.of(
            new Route(HttpMethod.GET, "/public/session", null),
            new Route(HttpMethod.DELETE, "/public/session", null),
            new Route(HttpMethod.GET, "/public/punishment", null),
            new Route(HttpMethod.POST, "/public/appeal", Map.of("statement", "hello")),
            new Route(HttpMethod.GET, "/public/appeal", null),
            new Route(HttpMethod.GET, "/public/conversation", null),
            new Route(HttpMethod.POST, "/public/messages", Map.of("text", "hello")));

    private static final List<Route> STAFF_ROUTES = List.of(
            new Route(HttpMethod.GET, "/auth/me", null),
            new Route(HttpMethod.GET, "/case", null),
            new Route(HttpMethod.GET, "/punishment", null),
            new Route(HttpMethod.GET, "/punishment/active", null),
            new Route(HttpMethod.GET, "/appeal", null),
            new Route(HttpMethod.GET, "/section", null),
            new Route(HttpMethod.GET, "/report", null),
            new Route(HttpMethod.POST, "/punishment/lookup", Map.of("banId", "ABCDEFGH")),
            new Route(HttpMethod.GET, "/prometheus", null),
            new Route(HttpMethod.GET, "/staff-link/me", null),
            new Route(HttpMethod.POST, "/discord/link/confirm", Map.of()),
            new Route(HttpMethod.POST, "/profile", Map.of()));

    private void assertRefused(Raw raw, String what) {
        assertTrue(raw.status() == 401 || raw.status() == 403, what + " must be refused by security, got " + raw.status() + " " + raw.body());
    }

    @Test
    void noStaffOrServiceTokenOpensARouteThatNeedsAPlayerSession() {
        for (String token : List.of(this.jwt.admin(), this.jwt.moderator(), this.jwt.viewer(), this.jwt.proxy(), this.jwt.metrics(),
                this.jwt.discord(), this.jwt.noGroup())) {
            for (Route route : SESSION_ROUTES) {
                assertRefused(call(route.method(), route.path(), token, route.body()), route.method() + " " + route.path() + " with a staff or service token");
            }
        }
    }

    @Test
    void aStaffTokenStillWorksOnItsOwnRoutes() {
        assertTrue(get("/section", this.jwt.viewer()).status() == 200, "precondition: the staff token is good");
    }

    @Test
    void aPlayerSessionOpensNothingOutsidePublic() {
        String token = sessionFor(punish("f1".repeat(64), "pub-access"));

        assertTrue(playerGet("/public/punishment", token).status() == 200, "precondition: the session is good");
        for (Route route : STAFF_ROUTES) {
            assertRefused(call(route.method(), route.path(), token, route.body()), route.method() + " " + route.path() + " with a player session");
        }
    }

    @Test
    void theLoginIsTheOnlyAnonymousPublicRoute() {
        for (Route route : SESSION_ROUTES) {
            assertRefused(call(route.method(), route.path(), null, route.body()), route.method() + " " + route.path() + " without a token");
        }
    }
}
