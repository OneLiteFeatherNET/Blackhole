package net.onelitefeather.blackhole.backend.rollback;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads the client spec that the client module generates from (the one the Gradle build pins) and checks the
 * rollback routes in it: the routes exist, and no request body names an actor. The actor is always the verified
 * token, never a field the caller sends.
 */
class RollbackSpecTest {

    private static final Pattern PINNED_SPEC = Pattern.compile("blackhole-api-([0-9.]+)\\.yml");
    private static final Set<String> ACTOR_LIKE = Set.of("actor", "oid", "source", "resolvedby", "reviewerid", "recordedby",
            "onbehalfof", "verifiedactor", "admin", "staff");

    private static Map<String, Object> pinnedSpec() throws IOException {
        String build = Files.readString(Path.of("..", "client", "build.gradle.kts"));
        Matcher pinned = PINNED_SPEC.matcher(build);
        assertTrue(pinned.find(), "the client build pins a spec file");
        Path spec = Path.of("..", "client", "specs", "blackhole-api-" + pinned.group(1) + ".yml");
        try (var reader = Files.newBufferedReader(spec)) {
            return new Yaml().load(reader);
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void theRollbackRoutesAreInThePinnedSpec() throws IOException {
        Map<String, Object> paths = (Map<String, Object>) pinnedSpec().get("paths");

        assertTrue(paths.containsKey("/admin/rollback/preview"), "POST preview is in the spec");
        assertTrue(paths.containsKey("/admin/rollback/{id}/execute"), "POST execute is in the spec");
        assertTrue(paths.containsKey("/admin/rollback/{id}"), "GET progress is in the spec");
        assertTrue(paths.containsKey("/admin/rollback/{id}/cancel"), "POST cancel is in the spec");
    }

    @SuppressWarnings("unchecked")
    @Test
    void noRollbackRequestBodyNamesAnActor() throws IOException {
        Map<String, Object> spec = pinnedSpec();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");

        for (Map.Entry<String, Object> path : paths.entrySet()) {
            if (!path.getKey().startsWith("/admin/rollback")) {
                continue;
            }
            for (Object operation : ((Map<String, Object>) path.getValue()).values()) {
                Map<String, Object> body = (Map<String, Object>) ((Map<String, Object>) operation).get("requestBody");
                if (body == null) {
                    continue;
                }
                String ref = (String) ((Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) body.get("content"))
                        .get("application/json")).get("schema")).get("$ref");
                String schemaName = ref.substring(ref.lastIndexOf('/') + 1);
                Map<String, Object> properties = (Map<String, Object>) ((Map<String, Object>) schemas.get(schemaName)).get("properties");
                for (String field : properties.keySet()) {
                    assertFalse(ACTOR_LIKE.contains(field.toLowerCase()),
                            schemaName + " must not take an actor from the caller, but has the field " + field);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void theRollbackPathsAreExactlyTheFourRoutes() throws IOException {
        Map<String, Object> paths = (Map<String, Object>) pinnedSpec().get("paths");
        List<String> rollbackPaths = paths.keySet().stream().filter(p -> p.startsWith("/admin/rollback")).toList();

        assertEquals(4, rollbackPaths.size(), "the four rollback paths and nothing else");
    }
}
