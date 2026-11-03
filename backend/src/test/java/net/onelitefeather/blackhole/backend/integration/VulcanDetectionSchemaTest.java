package net.onelitefeather.blackhole.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bundled vulcan/DETECTION v1 schema, read from the classpath as the service reads it. */
class VulcanDetectionSchemaTest {

    private static final String FULL = """
            {"check": "KillAura", "checkType": "A", "violationLevel": 14.5, "ping": 48, "tps": 19.8,
             "experimental": false, "detail": "Attack angle delta above threshold"}
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private Schema schema;

    @BeforeEach
    void loadSchema() {
        this.schema = new SchemaRegistry().find("vulcan", "DETECTION", 1).orElseThrow();
    }

    private boolean valid(String json) throws Exception {
        JsonNode node = this.mapper.readTree(json);
        return schema.validate(node).isEmpty();
    }

    @Test
    void aFullValidExampleIsAccepted() throws Exception {
        assertTrue(valid(FULL), "every field of the example is within its bounds");
    }

    @Test
    void aPayloadWithoutCheckIsRejected() throws Exception {
        assertFalse(valid("{\"violationLevel\": 3}"), "check is the one required field");
    }

    @Test
    void aDetailOfThreeHundredOneCharactersIsRejected() throws Exception {
        String detail = "x".repeat(301);

        assertFalse(valid("{\"check\": \"KillAura\", \"detail\": \"" + detail + "\"}"), "detail is at most 300 characters");
        assertTrue(valid("{\"check\": \"KillAura\", \"detail\": \"" + "x".repeat(300) + "\"}"), "300 characters is the limit");
    }

    @Test
    void anUnknownFieldIsRejected() throws Exception {
        assertFalse(valid("{\"check\": \"KillAura\", \"world\": \"survival\"}"), "additionalProperties is false");
    }

    @Test
    void aNegativeViolationLevelIsRejected() throws Exception {
        assertFalse(valid("{\"check\": \"KillAura\", \"violationLevel\": -1}"), "violationLevel starts at 0");
    }

    @Test
    void aTpsAboveTwentyPointFiveIsRejected() throws Exception {
        assertFalse(valid("{\"check\": \"KillAura\", \"tps\": 20.6}"), "tps cannot exceed the server's 20.5 sampling bound");
    }

    @Test
    void optionalFieldsMayBeAbsent() throws Exception {
        assertTrue(valid("{\"check\": \"KillAura\"}"), "only check is required");
    }

    @Test
    void optionalFieldsMayBeNull() throws Exception {
        assertTrue(valid("""
                {"check": "KillAura", "checkType": null, "violationLevel": null, "ping": null, "tps": null,
                 "experimental": null, "detail": null}
                """), "every optional field is nullable, so one schema serves several Vulcan versions");
    }
}
