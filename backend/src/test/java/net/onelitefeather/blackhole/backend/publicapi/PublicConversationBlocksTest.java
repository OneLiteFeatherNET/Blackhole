package net.onelitefeather.blackhole.backend.publicapi;

import net.onelitefeather.blackhole.backend.casetimeline.MessageContent;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicConversationDTO;
import net.onelitefeather.blackhole.backend.publicapi.service.PublicConversationService;
import net.onelitefeather.blackhole.backend.security.PlayerSession;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A conversation entry carries the stored source as {@code text} and the sanitised {@code blocks} parsed from it. */
class PublicConversationBlocksTest {

    private static final long NOW = 1_000L;

    private static PublicConversationService service(List<PublicConversationReader.Entry> entries) {
        PublicConversationReader reader = (punishment, afterSeq, limit) -> new PublicConversationReader.Page(entries, 9L);
        return new PublicConversationService(reader, null, null, null, 2000, 200, new MessageContent("onelitefeather.net"));
    }

    private static PublicConversationDTO.Page read(List<PublicConversationReader.Entry> entries) {
        PlayerSession session = new PlayerSession("hash", "p-1", PlayerScope.FULL, "csrf", NOW);
        return assertInstanceOf(PublicConversationDTO.Page.class, service(entries).read(session, null));
    }

    @Test
    void aMessageCarriesTheSourceAndItsBlocks() {
        PublicConversationDTO.Page page = read(List.of(new PublicConversationReader.Entry(1, NOW, "MESSAGE", "PLAYER", "m-1", "**hi** [x](https://example.org/)", false)));

        PublicConversationDTO.Entry entry = page.entries().getFirst();

        assertEquals("**hi** [x](https://example.org/)", entry.text(), "the stored source is unchanged");
        assertEquals(1, entry.blocks().size(), "one paragraph");
        assertEquals("paragraph", entry.blocks().getFirst().get("type"));
    }

    @Test
    void aRemovedMessageHasNeitherTextNorBlocks() {
        PublicConversationDTO.Page page = read(List.of(new PublicConversationReader.Entry(1, NOW, "MESSAGE", "STAFF", "m-1", null, true)));

        PublicConversationDTO.Entry entry = page.entries().getFirst();

        assertNull(entry.text());
        assertNull(entry.blocks(), "nothing is left to draw");
    }

    @Test
    void aMarkerHasNoBlocks() {
        PublicConversationDTO.Page page = read(List.of(new PublicConversationReader.Entry(2, NOW, "APPEAL_FILED", "SYSTEM", null, null, false)));

        assertNull(page.entries().getFirst().blocks(), "a marker is not Markdown");
    }

    @Test
    void aHostileMessageNeverBecomesALinkWithAScript() {
        PublicConversationDTO.Page page = read(List.of(new PublicConversationReader.Entry(1, NOW, "MESSAGE", "PLAYER", "m-1",
                "[x](javascript:alert(1)) <script>alert(1)</script>", false)));

        String tree = page.entries().getFirst().blocks().toString();

        assertEquals(false, tree.contains("type=link"), "no link node: " + tree);
        assertEquals(false, tree.contains("href"), "no href at all: " + tree);
    }
}
