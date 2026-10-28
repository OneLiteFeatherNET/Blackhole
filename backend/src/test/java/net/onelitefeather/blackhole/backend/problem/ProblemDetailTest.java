package net.onelitefeather.blackhole.backend.problem;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProblemDetailTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NOT_ELIGIBLE_TYPE = "https://blackhole.onelitefeather.net/problems/not-eligible";

    private static ProblemDetail sample(Map<String, Object> extensions) {
        return new ProblemDetail(NOT_ELIGIBLE_TYPE, "Not eligible", 409,
                "The appeal window for this punishment has not opened yet.", "/public/appeal", extensions);
    }

    private static Map<String, Object> wire(ProblemDetail problem) throws JsonProcessingException {
        return JSON.readValue(JSON.writeValueAsString(problem), new TypeReference<Map<String, Object>>() {
        });
    }

    @Test
    void theFiveStandardMembersArePresentWithTheirValues() throws JsonProcessingException {
        Map<String, Object> body = wire(sample(Map.of()));

        assertEquals(NOT_ELIGIBLE_TYPE, body.get("type"), "type is the problem type URI");
        assertEquals("Not eligible", body.get("title"), "title is the fixed title of the type");
        assertEquals(409, body.get("status"), "status is the response status as a number");
        assertEquals("The appeal window for this punishment has not opened yet.", body.get("detail"), "detail is the fixed sentence");
        assertEquals("/public/appeal", body.get("instance"), "instance is the request path");
    }

    @Test
    void extensionMembersAreFlattenedToTheTopLevel() throws JsonProcessingException {
        Map<String, Object> body = wire(sample(Map.of("code", "NOT_ELIGIBLE", "availableFrom", "2026-11-01T10:00:00Z")));

        assertEquals("NOT_ELIGIBLE", body.get("code"), "the machine code is a top-level member");
        assertEquals("2026-11-01T10:00:00Z", body.get("availableFrom"), "the extension data keeps its name");
        assertFalse(body.containsKey("extensions"), "the extensions map is not nested in the body");
    }

    @Test
    void aNullExtensionIsOmittedFromTheBody() throws JsonProcessingException {
        Map<String, Object> extensions = new HashMap<>();
        extensions.put("code", "NOT_ELIGIBLE");
        extensions.put("availableFrom", null);

        Map<String, Object> body = wire(sample(extensions));

        assertFalse(body.containsKey("availableFrom"), "an absent value is left out, not sent as null");
        assertEquals("NOT_ELIGIBLE", body.get("code"), "the other extension is still present");
    }

    @Test
    void theContentTypeIsApplicationProblemJson() {
        assertEquals("application/problem+json", ProblemDetail.CONTENT_TYPE, "the problem media type");
        assertTrue(ProblemDetail.CONTENT_TYPE.startsWith("application/problem"), "the media type is the RFC 9457 one");
    }
}
