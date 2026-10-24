package net.onelitefeather.blackhole.markdown;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordRendererTest {

    private static final String ZWSP = "​";

    private static String render(String source) {
        return DiscordRenderer.render(new MarkdownParser().parse(source));
    }

    @Test
    void everyoneAndHereCannotPing() {
        String out = render("@everyone @here hi");
        assertFalse(out.contains("@everyone"), "@everyone is broken up");
        assertFalse(out.contains("@here"), "@here is broken up");
        assertEquals("@" + ZWSP + "everyone @" + ZWSP + "here hi", out, "an at sign is followed by a zero-width space");
    }

    @Test
    void userRoleAndChannelFormsAreEscaped() {
        String out = render("<@123456789012345678> <@!1> <@&2> <#3>");
        assertFalse(out.replace("\\<", "").contains("<"), "every angle bracket is escaped, so no mention or channel form survives: " + out);
        assertFalse(out.replace("@" + ZWSP, "").contains("@"), "every at sign is broken up: " + out);
    }

    @Test
    void aMentionInsideACodeSpanIsLeftAlone() {
        assertEquals("`@everyone`", render("`@everyone`"), "Discord does not ping from inside code");
    }

    @Test
    void aLinkTargetWithUserInfoIsNotRewrittenIntoAWorkingLink() {
        String out = render("see https://user@evil.example/ here");
        assertFalse(out.contains("https://user"), "the rejected URL cannot autolink in Discord: " + out);
    }

    @Test
    void aLinkIsWrittenWithAngleBracketsSoNoEmbedAppears() {
        assertEquals("docs <https://example.org/>", render("[docs](https://example.org/)"), "text, then the URL in angle brackets");
    }

    @Test
    void aCodeBlockWithAFenceInsideGetsALongerFence() {
        String out = render("````\n```\nx\n````");
        assertEquals("````\n```\nx\n````", out, "the fence is one longer than the longest run inside");
    }

    @Test
    void inlineCodeStartingWithABacktickIsPadded() {
        assertEquals("`` `x ``", render("`` `x ``"), "padding keeps the backtick from merging with the fence");
    }

    @Test
    void shortMessagesAreOneChunk() {
        assertEquals(List.of("**hi**"), DiscordRenderer.chunks(new MarkdownParser().parse("**hi**"), 2000), "one chunk");
    }

    @Test
    void anEmptyTreeIsNoChunk() {
        assertEquals(List.of(), DiscordRenderer.chunks(List.of(), 2000), "nothing to post");
    }

    @Test
    void longMessagesSplitAtBlockBoundaries() {
        String source = ("x".repeat(900) + "\n\n").repeat(5);
        List<String> chunks = DiscordRenderer.chunks(new MarkdownParser().parse(source), 2000);
        assertEquals(3, chunks.size(), "two paragraphs fit in a chunk, so five make three");
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 2000, "a chunk fits Discord's limit");
            assertTrue(chunk.startsWith("x") && chunk.endsWith("x"), "a chunk holds whole paragraphs");
        }
    }

    @Test
    void oneBlockLongerThanTheLimitIsSplitAtLines() {
        String source = "> " + "word ".repeat(300) + "\n> " + "word ".repeat(300);
        List<String> chunks = DiscordRenderer.chunks(new MarkdownParser().parse(source), 2000);
        assertTrue(chunks.size() >= 2, "the quote does not fit in one post");
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 2000, "a chunk fits Discord's limit, found " + chunk.length());
        }
    }

    @Test
    void aCodeBlockLongerThanTheLimitIsSplitAndEachPartKeepsItsFence() {
        String source = "```\n" + "line of code\n".repeat(400) + "```";
        List<String> chunks = DiscordRenderer.chunks(new MarkdownParser().parse(source), 2000);
        assertTrue(chunks.size() >= 3, "the block needs several posts");
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 2000, "a chunk fits Discord's limit, found " + chunk.length());
            assertTrue(chunk.startsWith("```\n") && chunk.endsWith("\n```"), "every part is a closed code block");
        }
    }

    @Test
    void oneLineLongerThanTheLimitIsCutHard() {
        List<String> chunks = DiscordRenderer.chunks(new MarkdownParser().parse("y".repeat(5000)), 2000);
        assertEquals(3, chunks.size(), "5000 characters need three posts");
        assertEquals("y".repeat(5000), String.join("", chunks), "nothing is lost");
    }
}
