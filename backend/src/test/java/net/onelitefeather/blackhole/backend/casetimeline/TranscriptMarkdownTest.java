package net.onelitefeather.blackhole.backend.casetimeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The admin transcript keeps the Markdown source as written and says in the JSON that it is Markdown. */
class TranscriptMarkdownTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SOURCE = "**bold** [a link](https://example.org/a)\n> quoted\n<b>x</b>";

    private static CaseReader.Entry entry(long seq, String type, CaseEventPayload payload) {
        CaseEventRecord event = new CaseEventRecord(UUID.nameUUIDFromBytes(("e" + seq).getBytes()), seq, "p-1", type, 1, seq * 1000,
                CaseEventOrigin.LIVE, ActorTrust.PLAYER, null, null, null, "m-1", "PLAYER", payload.toMap(), null, null);
        return new CaseReader.Entry(event, null);
    }

    private static CaseEventPayload added(String text) {
        return new CaseEventPayload.MessageAdded("m-1", null, MessageAuthorKind.PLAYER, null, MessageChannel.PORTAL, text);
    }

    @Test
    void theJsonIsSchemaVersionTwoAndMessagesAreMarkdown() throws Exception {
        TranscriptRenderer.Rendered rendered = TranscriptRenderer.render("p-1", "AB12", false, List.of(
                entry(1, "message.added", added(SOURCE)), entry(2, "message.edited", new CaseEventPayload.MessageEdited("m-1", "*second*"))));

        JsonNode document = JSON.readTree(rendered.json());

        assertEquals(2, document.get("schemaVersion").asInt(), "entries gained a format");
        JsonNode first = document.get("entries").get(0);
        assertEquals("markdown", first.get("format").asText(), "the text of a message is Markdown");
        assertEquals(SOURCE, first.get("payload").get("text").asText(), "the source is exported as stored");
        assertEquals("markdown", document.get("entries").get(1).get("format").asText(), "an edit is Markdown as well");
    }

    @Test
    void theTextFileKeepsTheSource() {
        TranscriptRenderer.Rendered rendered = TranscriptRenderer.render("p-1", "AB12", false, List.of(entry(1, "message.added", added(SOURCE))));

        assertTrue(rendered.text().contains("    **bold** [a link](https://example.org/a)\n    > quoted\n    <b>x</b>"), rendered.text());
    }

    @Test
    void aRedactedMessageHasNoSourceAndNoFormat() throws Exception {
        CaseEventRecord stored = new CaseEventRecord(UUID.nameUUIDFromBytes("r".getBytes()), 1, "p-1", "message.added", 1, 1000, CaseEventOrigin.LIVE,
                ActorTrust.PLAYER, null, null, null, "m-1", "PLAYER", new CaseEventPayload.MessageAdded("m-1", null, MessageAuthorKind.PLAYER, null,
                MessageChannel.PORTAL, null).toMap(), 5L, null);

        JsonNode document = JSON.readTree(TranscriptRenderer.render("p-1", "AB12", false, List.of(new CaseReader.Entry(stored, null))).json());

        assertNull(document.get("entries").get(0).get("payload").get("text"), "the text is gone");
        assertEquals(true, document.get("entries").get(0).get("redacted").asBoolean());
    }

    @Test
    void anEntryThatIsNotAMessageHasNoFormat() throws Exception {
        CaseEventRecord filed = new CaseEventRecord(UUID.nameUUIDFromBytes("f".getBytes()), 1, "p-1", "appeal.filed", 1, 1000, CaseEventOrigin.LIVE,
                ActorTrust.SYSTEM, null, null, null, "a-1", "STAFF", new CaseEventPayload.AppealFiled("a-1", true).toMap(), null, null);

        JsonNode document = JSON.readTree(TranscriptRenderer.render("p-1", "AB12", false, List.of(new CaseReader.Entry(filed, null))).json());

        assertNull(document.get("entries").get(0).get("format"), "only messages hold Markdown");
    }
}
