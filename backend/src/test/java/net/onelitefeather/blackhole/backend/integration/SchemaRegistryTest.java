package net.onelitefeather.blackhole.backend.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaRegistryTest {

    private static final String TUPLE_2020_12 = """
            {"$schema": "https://json-schema.org/draft/2020-12/schema", "type": "array", "prefixItems": [{"type": "string"}]}
            """;

    @Test
    void theBundledVulcanDetectionVersionOneIsRegistered() {
        SchemaRegistry registry = new SchemaRegistry();

        assertTrue(registry.find("vulcan", "DETECTION", 1).isPresent(), "vulcan/DETECTION v1 is loaded at startup");
        assertEquals(java.util.List.of(new SchemaRegistry.Key("vulcan", "DETECTION", 1)), registry.keys());
    }

    @Test
    void aTripleWithNoSchemaIsAbsent() {
        SchemaRegistry registry = new SchemaRegistry();

        assertFalse(registry.find("vulcan", "DETECTION", 2).isPresent(), "another version");
        assertFalse(registry.find("grim", "DETECTION", 1).isPresent(), "another source system");
        assertFalse(registry.find("vulcan", "CHAT", 1).isPresent(), "another kind");
    }

    @Test
    void aSchemaFileNamedOtherwiseThanSourceKindVersionIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> SchemaRegistry.of(Map.of("Vulcan/DETECTION/v1.json", "{}")));
        assertThrows(IllegalArgumentException.class, () -> SchemaRegistry.of(Map.of("vulcan/DETECTION/1.json", "{}")));
    }

    @Test
    void aSchemaDeclaring2020TwelveIsValidatedWithThatDialect() throws Exception {
        // prefixItems is a 2020-12 keyword: under draft 7 it is ignored and [1] would pass, so a pass here means 2020-12 is in force.
        SchemaRegistry registry = SchemaRegistry.of(Map.of("vulcan/TUPLE/v1.json", TUPLE_2020_12));
        var schema = registry.find("vulcan", "TUPLE", 1).orElseThrow();
        ObjectMapper mapper = new ObjectMapper();

        assertFalse(schema.validate(mapper.readTree("[1]")).isEmpty(), "a number in the first position is refused");
        assertTrue(schema.validate(mapper.readTree("[\"a\"]")).isEmpty(), "a string in the first position is accepted");
    }
}
