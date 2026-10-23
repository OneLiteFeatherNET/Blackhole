package net.onelitefeather.blackhole.backend.security;

import io.micronaut.security.annotation.Secured;
import io.micronaut.web.router.Router;
import net.onelitefeather.blackhole.backend.ApiIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walks every route the application registers and compares its roles with the permission matrix of
 * the design (add-team-sso, decision 3). A new endpoint without a rule, or with other roles than the
 * matrix says, fails the build.
 */
@Tag("integration")
class RouteCoverageIntegrationTest extends ApiIntegrationTest {

    private static final Set<String> V = Set.of("VIEWER");
    private static final Set<String> P = Set.of("PROXY");
    private static final Set<String> VP =Set.of("VIEWER", "PROXY");
    private static final Set<String> M = Set.of("MODERATOR");
    private static final Set<String> MP = Set.of("MODERATOR", "PROXY");
    private static final Set<String> A = Set.of("ADMIN");
    private static final Set<String> AUTHENTICATED = Set.of("isAuthenticated()");

    /** The matrix: route -> roles that may call it (any one of them). */
    private static final Map<String, Set<String>> MATRIX = Map.ofEntries(
            // read-only
            Map.entry("GET /punishment", VP),
            Map.entry("GET /punishment/short/{shortId}", VP),
            Map.entry("GET /profile", VP),
            Map.entry("GET /profile/{owner}", VP),
            Map.entry("GET /elo/{owner}", VP),
            Map.entry("GET /elo/{owner}/history", V),
            Map.entry("GET /report", V),
            Map.entry("GET /appeal", V),
            Map.entry("GET /section", VP),
            Map.entry("GET /section/{key}", VP),
            Map.entry("GET /template", VP),
            Map.entry("GET /template/{identifier}", VP),
            Map.entry("GET /dashboard/summary", V),
            Map.entry("GET /player/resolve/{name}", VP),
            Map.entry("GET /punishment/{reference}/evidence", VP),
            Map.entry("GET /punishment/{reference}/evidence/count", VP),
            Map.entry("GET /evidence", VP),
            Map.entry("GET /evidence/{identifier}", V),
            Map.entry("GET /evidence/{identifier}/content", V),
            Map.entry("GET /cloudnet/catalogue", V),
            Map.entry("GET /case", V),
            Map.entry("GET /case/{reference}", V),
            Map.entry("GET /case/{reference}/timeline", V),
            Map.entry("GET /evasion/{owner}/sightings", V),
            // moderation
            Map.entry("PATCH /case/{reference}", M),
            Map.entry("POST /case/{reference}/notes", M),
            Map.entry("PUT /case/{reference}/notes/{noteId}", M),
            Map.entry("GET /punishment/{reference}/ban-id", MP),
            Map.entry("POST /punishment/lookup", MP),
            Map.entry("POST /punishment/apply", MP),
            Map.entry("POST /punishment/preview", M),
            Map.entry("POST /punishment/revoke", MP),
            Map.entry("POST /report/{identifier}/resolve", M),
            Map.entry("POST /appeal/{identifier}/review", M),
            Map.entry("POST /elo/{owner}/adjust", M),
            Map.entry("POST /evidence", MP),
            Map.entry("POST /evidence/file", MP),
            Map.entry("POST /profile", M),
            Map.entry("POST /profile/update/{owner}", M),
            // intake (the proxy's routes)
            Map.entry("POST /report", MP),
            Map.entry("POST /appeal", MP),
            Map.entry("POST /elo/chat", MP),
            Map.entry("POST /evasion/record", MP),
            Map.entry("POST /profile/{owner}/session", MP),
            // admin configuration
            Map.entry("POST /section", A),
            Map.entry("POST /section/update", A),
            Map.entry("POST /section/{key}/disable", A),
            Map.entry("POST /template", A),
            Map.entry("POST /template/update", A),
            Map.entry("DELETE /template/delete/{identifier}", A),
            Map.entry("POST /admin/import/vanilla", A),
            Map.entry("GET /admin/staff-actor", A),
            Map.entry("POST /admin/staff-actor/{oid}/anonymize", A),
            // erasure and destructive
            Map.entry("POST /case/{reference}/notes/{noteId}/redact", A),
            Map.entry("DELETE /evidence/{identifier}", A),
            Map.entry("DELETE /evidence/owner/{owner}", A),
            Map.entry("DELETE /profile/delete/{owner}", A),
            // staff account link: own (any staff role), the proxy's confirmation and lookup, admin
            Map.entry("POST /staff-link/code", V),
            Map.entry("GET /staff-link/me", V),
            Map.entry("DELETE /staff-link/me", V),
            Map.entry("PUT /cloudnet/catalogue", P),
            Map.entry("POST /staff-link/confirm", P),
            Map.entry("GET /staff-link/by-minecraft/{uuid}", P),
            Map.entry("GET /admin/staff-link", A),
            Map.entry("DELETE /admin/staff-link/{oid}", A),
            // identity
            Map.entry("GET /auth/me", AUTHENTICATED)
    );

    /** Routes without @Secured, protected by the intercept-url-map or public (see application.yml). */
    private static final Set<String> CONFIGURED_IN_YAML = Set.of("GET /health", "GET /health/{selector}", "GET /prometheus");

    @Test
    void everyRouteHasARuleAndItsRolesEqualTheMatrix() {
        Map<String, Set<String>> actual = new TreeMap<>();
        Set<String> withoutRule = new TreeSet<>();
        this.context.getBean(Router.class).uriRoutes().filter(route -> !"HEAD".equals(route.getHttpMethodName())).forEach(route -> {
            String key = route.getHttpMethodName() + " " + route.getUriMatchTemplate();
            Set<String> roles = Set.of(route.getAnnotationMetadata().stringValues(Secured.class));
            if (roles.isEmpty()) {
                withoutRule.add(key);
            } else {
                actual.put(key, roles);
            }
        });

        assertEquals(new TreeMap<>(MATRIX), actual, "routes with an @Secured rule must equal the matrix");
        Set<String> unexpected = withoutRule.stream().filter(route -> !CONFIGURED_IN_YAML.contains(route)).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(Set.of(), unexpected, "routes without any rule (they would be rejected)");
    }

    @Test
    void noRouteIsRegisteredUnderThePublicPrefix() {
        boolean any = this.context.getBean(Router.class).uriRoutes()
                .anyMatch(route -> route.getUriMatchTemplate().toString().startsWith("/public"));

        assertTrue(!any, "/public/** is reserved for the player portal");
    }
}
