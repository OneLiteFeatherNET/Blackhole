package net.onelitefeather.blackhole.backend.punishment.controller;

import net.onelitefeather.blackhole.backend.BlackholeApplication;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /template} answers with Micronaut's {@code Page}, so the published spec must describe a
 * page object with the templates under {@code content} - not a bare array, which the generated
 * client could not parse. Reads the spec the annotation processor generated.
 */
class TemplateListSpecTest {

    private static Map<String, Object> spec;

    @BeforeAll
    static void loadGeneratedSpec() throws IOException, URISyntaxException {
        // Through the application class, so another jar's META-INF/swagger cannot be picked up instead.
        Path directory = Path.of(BlackholeApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .resolve("META-INF/swagger");
        Path file;
        try (Stream<Path> files = Files.list(directory)) {
            file = files.filter(candidate -> candidate.getFileName().toString().matches("blackhole-api-.*\\.yml"))
                    .findFirst().orElseThrow(() -> new AssertionError("no generated spec in " + directory));
        }
        try (InputStream in = Files.newInputStream(file)) {
            spec = new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> child(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        assertNotNull(value, "the spec has no '" + key + "'");
        return (Map<String, Object>) value;
    }

    @Test
    void listingTemplatesIsDescribedAsAPageObjectNotAnArray() {
        Map<String, Object> ok = child(child(child(child(spec, "paths"), "/template"), "get"), "responses");
        Map<String, Object> body = child(child(child(child(ok, "200"), "content"), "application/json"), "schema");

        assertEquals("#/components/schemas/PunishTemplatePage", body.get("$ref"),
                "GET /template returns a page, so its 200 body must reference the page schema, got " + body);
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePageSchemaHoldsTheTemplatesUnderContent() {
        Map<String, Object> page = child(child(child(spec, "components"), "schemas"), "PunishTemplatePage");
        Map<String, Object> content = child(child(page, "properties"), "content");

        assertEquals("array", content.get("type"), "content is the list of templates");
        assertEquals("#/components/schemas/PunishTemplateResponse", child(content, "items").get("$ref"),
                "every element of content is a template");
        assertTrue(((List<String>) page.get("required")).contains("content"), "content is always present");
        assertTrue(child(page, "properties").containsKey("totalPages"), "totalPages tells a client whether to read on");
    }
}
