package net.onelitefeather.blackhole.backend.evidence.validation;

import net.onelitefeather.blackhole.backend.evidence.EvidenceConfig;
import net.onelitefeather.blackhole.backend.evidence.EvidenceKind;
import net.onelitefeather.blackhole.backend.evidence.dto.EvidenceErrorKind;
import net.onelitefeather.blackhole.backend.evidence.validation.PayloadValidator.Validated;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadValidatorTest {

    private static final String HASH = "ab".repeat(64);

    private final EvidenceConfig config = new EvidenceConfig();
    private final PayloadValidator validator = new PayloadValidator(this.config);

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> line(Object timestamp, Object speaker, Object text) {
        return map("timestamp", timestamp, "speaker", speaker, "text", text);
    }

    private void assertRejected(Validated result, EvidenceErrorKind kind, String messagePart) {
        assertNotNull(result.error(), "expected a rejection mentioning '" + messagePart + "'");
        assertEquals(kind, result.error().kind(), result.error().errorMessage());
        assertTrue(result.error().errorMessage().contains(messagePart),
                "message should contain '" + messagePart + "' but was: " + result.error().errorMessage());
        assertNull(result.payload(), "nothing may be stored for a rejected payload");
    }

    // ---- ANTICHEAT -----------------------------------------------------------------------------------------

    @Test
    void aMinimalAnticheatPayloadIsAccepted() {
        Validated result = this.validator.validate(EvidenceKind.ANTICHEAT, null, map("check", "fly"));

        assertTrue(result.accepted(), "a payload with only the required check is valid");
        assertEquals("fly", result.payload().get("check"));
    }

    @Test
    void aFullAnticheatPayloadKeepsAllDeclaredFields() {
        Validated result = this.validator.validate(EvidenceKind.ANTICHEAT, null, map(
                "check", "speed", "violationLevel", 12.5, "server", "survival-1", "detail", "moved too fast",
                "values", map("deltaX", 3.2, "onGround", false, "mode", "sprint")));

        assertTrue(result.accepted(), () -> result.error().errorMessage());
        assertEquals(12.5, result.payload().get("violationLevel"));
        assertEquals(3, ((Map<?, ?>) result.payload().get("values")).size());
    }

    @Test
    void anUndeclaredAnticheatPropertyIsABadRequestNamingTheProperty() {
        Validated result = this.validator.validate(EvidenceKind.ANTICHEAT, null, map("check", "fly", "playerName", "Notch"));

        assertRejected(result, EvidenceErrorKind.VALIDATION, "undeclared property /playerName");
    }

    @Test
    void anAnticheatPayloadWithoutACheckIsRejected() {
        assertRejected(this.validator.validate(EvidenceKind.ANTICHEAT, null, map("server", "s")),
                EvidenceErrorKind.VALIDATION, "/check is required");
    }

    @Test
    void aNestedAnticheatValuesMapIsRejectedBecauseItMustBeFlat() {
        Validated result = this.validator.validate(EvidenceKind.ANTICHEAT, null,
                map("check", "fly", "values", map("nested", map("a", 1))));

        assertRejected(result, EvidenceErrorKind.VALIDATION, "/values/nested");
    }

    @Test
    void moreThanThirtyTwoAnticheatValuesAreRejected() {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < 33; i++) {
            values.put("k" + i, i);
        }

        assertRejected(this.validator.validate(EvidenceKind.ANTICHEAT, null, map("check", "fly", "values", values)),
                EvidenceErrorKind.VALIDATION, "at most 32 entries");
    }

    @Test
    void aPayloadOverTheSizeLimitIsTooLargeAndNothingIsStored() {
        this.config.setMaxPayloadBytes(200);
        Validated result = this.validator.validate(EvidenceKind.ANTICHEAT, null, map("check", "fly", "detail", "x".repeat(300)));

        assertRejected(result, EvidenceErrorKind.PAYLOAD_TOO_LARGE, "limit is 200");
    }

    // ---- PII ----------------------------------------------------------------------------------------------

    @Test
    void aCanonicalUuidInAStructuredValueIsUnprocessableAndNamesThePointer() {
        Validated result = this.validator.validate(EvidenceKind.ANTICHEAT, null,
                map("check", "fly", "values", map("player", "123e4567-e89b-12d3-a456-426614174000")));

        assertRejected(result, EvidenceErrorKind.UNPROCESSABLE, "/values/player");
    }

    @Test
    void anIpv4InAStructuredValueIsUnprocessable() {
        assertRejected(this.validator.validate(EvidenceKind.ANTICHEAT, null, map("check", "fly", "detail", "from 203.0.113.5")),
                EvidenceErrorKind.UNPROCESSABLE, "/detail");
    }

    @Test
    void anIpv6InAStructuredValueIsUnprocessable() {
        assertRejected(this.validator.validate(EvidenceKind.ANTICHEAT, null, map("check", "fly", "server", "2001:db8::8a2e:370:7334")),
                EvidenceErrorKind.UNPROCESSABLE, "/server");
    }

    @Test
    void anOwnerHashInAStructuredValueIsAllowed() {
        assertTrue(this.validator.validate(EvidenceKind.ANTICHEAT, null, map("check", "fly", "values", map("target", HASH))).accepted(),
                "a 128 character hex hash is neither a UUID nor an IP");
    }

    // ---- CHAT_LOG ------------------------------------------------------------------------------------------

    @Test
    void ipAddressesInChatTextAreRedactedNotRejected() {
        Validated result = this.validator.validate(EvidenceKind.CHAT_LOG, null,
                map("lines", List.of(line(1700000000000L, HASH, "join 203.0.113.5 now"))));

        assertTrue(result.accepted(), () -> result.error().errorMessage());
        Map<?, ?> stored = (Map<?, ?>) ((List<?>) result.payload().get("lines")).get(0);
        assertEquals("join [ip] now", stored.get("text"));
    }

    @Test
    void aUuidTypedIntoChatTextIsLeftAlone() {
        Validated result = this.validator.validate(EvidenceKind.CHAT_LOG, null,
                map("lines", List.of(line(1L, null, "my id is 123e4567-e89b-12d3-a456-426614174000"))));

        assertTrue(result.accepted(), "a UUID typed in chat is not an identifier of a known player");
        Map<?, ?> stored = (Map<?, ?>) ((List<?>) result.payload().get("lines")).get(0);
        assertEquals("my id is 123e4567-e89b-12d3-a456-426614174000", stored.get("text"));
    }

    @Test
    void moreChatLinesThanTheLimitAreRejected() {
        this.config.setMaxChatLines(3);
        List<Object> lines = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            lines.add(line((long) i, "Spieler A", "hi"));
        }

        assertRejected(this.validator.validate(EvidenceKind.CHAT_LOG, null, map("lines", lines)),
                EvidenceErrorKind.VALIDATION, "between 1 and 3 lines");
    }

    @Test
    void anEmptyChatLogIsRejected() {
        assertRejected(this.validator.validate(EvidenceKind.CHAT_LOG, null, map("lines", List.of())),
                EvidenceErrorKind.VALIDATION, "between 1 and");
    }

    @Test
    void aChatSpeakerMayBeAnAliasOrAHashButNotAUuid() {
        assertTrue(this.validator.validate(EvidenceKind.CHAT_LOG, null, map("lines", List.of(line(1L, "Spieler A", "hi")))).accepted(),
                "an alias is allowed");
        assertRejected(this.validator.validate(EvidenceKind.CHAT_LOG, null,
                        map("lines", List.of(line(1L, "123e4567-e89b-12d3-a456-426614174000", "hi")))),
                EvidenceErrorKind.UNPROCESSABLE, "/lines/0/speaker");
    }

    @Test
    void anUndeclaredChatLinePropertyIsRejected() {
        Map<String, Object> bad = line(1L, null, "hi");
        bad.put("name", "Notch");

        assertRejected(this.validator.validate(EvidenceKind.CHAT_LOG, null, map("lines", List.of(bad))),
                EvidenceErrorKind.VALIDATION, "undeclared property /lines/0/name");
    }

    // ---- REPLAY / LINK -------------------------------------------------------------------------------------

    @Test
    void aReplayLinkWithADescriptionIsAccepted() {
        Validated result = this.validator.validate(EvidenceKind.REPLAY, "https://replays.example.org/r/42", map("description", "round 3"));

        assertTrue(result.accepted(), () -> result.error().errorMessage());
        assertEquals("https://replays.example.org/r/42", result.reference());
        assertEquals("round 3", result.payload().get("description"));
    }

    @Test
    void aLinkWithoutAReferenceIsRejected() {
        assertRejected(this.validator.validate(EvidenceKind.LINK, null, null), EvidenceErrorKind.VALIDATION, "reference is required");
    }

    @Test
    void aLinkWithAnUndeclaredPayloadPropertyIsRejected() {
        assertRejected(this.validator.validate(EvidenceKind.LINK, "https://example.org/x", map("title", "t")),
                EvidenceErrorKind.VALIDATION, "undeclared property /title");
    }

    @Test
    void aReferenceOnAnAnticheatItemIsRejected() {
        assertRejected(this.validator.validate(EvidenceKind.ANTICHEAT, "https://example.org", map("check", "fly")),
                EvidenceErrorKind.VALIDATION, "only accepted for REPLAY and LINK");
    }

    @Test
    void fileEvidenceCannotBeAttachedAsJson() {
        assertRejected(this.validator.validate(EvidenceKind.FILE, null, map("a", 1)), EvidenceErrorKind.VALIDATION, "/evidence/file");
    }
}
