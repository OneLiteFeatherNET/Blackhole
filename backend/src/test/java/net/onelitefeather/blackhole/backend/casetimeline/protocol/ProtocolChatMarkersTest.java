package net.onelitefeather.blackhole.backend.casetimeline.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.casetimeline.ActorTrust;
import net.onelitefeather.blackhole.backend.casetimeline.CaseEventPayload;
import net.onelitefeather.blackhole.backend.casetimeline.CaseReader;
import net.onelitefeather.blackhole.backend.casetimeline.MessageAttachment;
import net.onelitefeather.blackhole.backend.casetimeline.MessageAuthorKind;
import net.onelitefeather.blackhole.backend.casetimeline.MessageChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.onelitefeather.blackhole.backend.casetimeline.protocol.ProtocolFixtures.APPEAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Files and shares in the stored protocol: marker lines only, never bytes, hashes of events or the shared lines. */
class ProtocolChatMarkersTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID FILE = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final String SHA = "ab12cd34ef56" + "0".repeat(52);

    private static ProtocolSource.Appeal appeal() {
        return ProtocolFixtures.appeal(APPEAL, AppealStatus.DENIED, 1_000, 9_000);
    }

    private static CaseReader.Entry messageWithFile(long seq, String text) {
        CaseEventPayload.MessageAdded payload = new CaseEventPayload.MessageAdded("m-1", APPEAL.toString(), MessageAuthorKind.PLAYER, null,
                MessageChannel.PORTAL, text,
                List.of(new MessageAttachment("file", null, "shot.png", "image/png", 1234L, 32, 20, FILE.toString(), "PLAYER")), List.of());
        return ProtocolFixtures.entry(ProtocolFixtures.event(seq, 2_000, "message.added", ActorTrust.PLAYER, "m-1", "PLAYER", payload.toMap()), null);
    }

    private static CaseReader.Entry shared(long seq, long at, String mode, Integer lines) {
        return ProtocolFixtures.entry(ProtocolFixtures.event(seq, at, "evidence.shared", ActorTrust.VERIFIED, "share-1", "PLAYER",
                new CaseEventPayload.EvidenceShared("share-1", "CHAT_LOG", mode, lines).toMap()), "Reviewer Rita");
    }

    private static CaseReader.Entry revoked(long seq, long at) {
        return ProtocolFixtures.entry(ProtocolFixtures.event(seq, at, "evidence.share_revoked", ActorTrust.VERIFIED, "share-1", "PLAYER",
                new CaseEventPayload.EvidenceShareRevoked("share-1", "PRIVACY").toMap()), "Reviewer Rita");
    }

    private static String text(ProtocolModel model) {
        return new String(ProtocolRenderer.render(model, 1, 5_000).text(), StandardCharsets.UTF_8);
    }

    private static ProtocolModel model(Map<String, String> digests, CaseReader.Entry... entries) {
        return ProtocolModel.build(appeal(), List.of(appeal()), List.of(entries), digests);
    }

    @Test
    void aChatFileIsOneMarkerLineWithTypeSizeHashStartAndName() {
        String text = text(model(Map.of(FILE.toString(), SHA), messageWithFile(1, "see this")));

        assertTrue(text.contains("[file: image/png, 1234 bytes, sha256 ab12cd34ef56, shot.png]"), text);
        assertFalse(text.contains(SHA), "only the start of the hash is shown");
    }

    @Test
    void theJsonCarriesTheSameMarkerAndProvenance() throws Exception {
        ProtocolModel model = model(Map.of(FILE.toString(), SHA), messageWithFile(1, "see this"));

        JsonNode file = JSON.readTree(ProtocolRenderer.render(model, 1, 5_000).json()).get("entries").get(0).get("attachments").get(0);

        assertEquals("ab12cd34ef56", file.get("sha256Prefix").asText());
        assertEquals("PLAYER", file.get("provenance").asText());
        assertEquals("[file: image/png, 1234 bytes, sha256 ab12cd34ef56, shot.png]", file.get("marker").asText());
        assertFalse(file.has("attachmentId"), "an internal id is not part of the record");
    }

    @Test
    void aFileWhoseEvidenceIsGoneStillGetsAMarker() {
        String text = text(model(Map.of(), messageWithFile(1, "see this")));

        assertTrue(text.contains("sha256 unavailable"), text);
    }

    @Test
    void theHashInTheFileDigestsDoesNotChangeTheSourceDigest() {
        ProtocolModel with = model(Map.of(FILE.toString(), SHA), messageWithFile(1, "x"));
        ProtocolModel without = model(Map.of(), messageWithFile(1, "x"));

        assertEquals(with.digest(), without.digest());
    }

    @Test
    void aShareAndARevokeAreMarkerLinesWithoutTheLines() {
        String text = text(model(Map.of(), shared(1, 3_000, "EXCERPT", 5), revoked(2, 4_000)));

        assertTrue(text.contains("[evidence shared: chat excerpt, 5 lines]"), text);
        assertTrue(text.contains("[evidence share withdrawn]"), text);
    }

    @Test
    void oneLineAndAScreenshotReadNaturally() {
        String text = text(model(Map.of(), shared(1, 3_000, "EXCERPT", 1), shared(2, 3_500, "FILE", null)));

        assertTrue(text.contains("[evidence shared: chat excerpt, 1 line]"), text);
        assertTrue(text.contains("[evidence shared: screenshot]"), text);
    }

    @Test
    void sharesBelongToTheAppealWhoseWindowTheyHappenedIn() {
        ProtocolSource.Appeal first = ProtocolFixtures.appeal(APPEAL, AppealStatus.DENIED, 1_000, 5_000);
        ProtocolSource.Appeal second = ProtocolFixtures.appeal(UUID.fromString("00000000-0000-0000-0000-0000000000a2"), AppealStatus.DENIED, 6_000, 9_000);
        List<CaseReader.Entry> entries = List.of(shared(1, 3_000, "EXCERPT", 2), shared(2, 7_000, "EXCERPT", 3));

        String firstText = text(ProtocolModel.build(first, List.of(first, second), entries, Map.of()));

        assertTrue(firstText.contains("2 lines"), firstText);
        assertFalse(firstText.contains("3 lines"), "the later share is the second appeal's: " + firstText);
    }

    @Test
    void theProtocolNeverHoldsTheSharedContent() throws Exception {
        ProtocolModel model = model(Map.of(), shared(1, 3_000, "EXCERPT", 5));

        String everything = text(model) + new String(ProtocolRenderer.render(model, 1, 5_000).json(), StandardCharsets.UTF_8);

        assertFalse(everything.contains("Alice"), "no speaker, no line");
    }
}
