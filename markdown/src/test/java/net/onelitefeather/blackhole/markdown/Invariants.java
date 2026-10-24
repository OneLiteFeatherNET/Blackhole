package net.onelitefeather.blackhole.markdown;

import java.net.URI;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What must hold for every input, whatever it is: the checks shared by the hostile corpus and the random test. */
final class Invariants {

    private static final Set<String> TYPES = Set.of("text", "br", "strong", "em", "del", "code", "link", "paragraph", "code_block", "quote", "list", "item");

    /** Containers (3) plus inline kinds (at most 3 pending) plus the leaf: far below this. */
    private static final int MAX_DEPTH = 12;

    private Invariants() {
    }

    static void assertSafe(String label, String source) {
        MarkdownParser parser = new MarkdownParser();
        List<Node> tree = assertDoesNotThrow(() -> parser.parse(source), label + ": the parser must not throw");
        assertNotNull(tree, label + ": a tree is returned");
        int[] count = {0};
        for (Node node : tree) {
            assertTrue(node.block(), label + ": the top level holds blocks only, found " + node.type());
            walk(label, node, 1, count);
        }
        assertTrue(count[0] <= MarkdownParser.MAX_NODES + 4, label + ": at most " + MarkdownParser.MAX_NODES + " nodes, found " + count[0]);
        long budget = 40L * source.length() + 2_000;
        assertTrue(parser.steps() <= budget, label + ": " + parser.steps() + " steps for " + source.length() + " characters");
        int bound = 8 * source.length() + 1_000;
        String discord = assertDoesNotThrow(() -> DiscordRenderer.render(tree), label + ": the discord renderer must not throw");
        String plain = assertDoesNotThrow(() -> PlainTextRenderer.render(tree), label + ": the plain renderer must not throw");
        assertTrue(discord.length() <= bound, label + ": discord output is bounded, " + discord.length());
        assertTrue(plain.length() <= bound, label + ": plain output is bounded, " + plain.length());
        assertEquals(Node.toMaps(tree), Node.toMaps(assertDoesNotThrow(() -> new MarkdownParser().parse(source))), label + ": parsing is deterministic");
        assertDoesNotThrow(() -> DiscordRenderer.chunks(tree, 2000), label + ": chunking must not throw");
        for (String chunk : DiscordRenderer.chunks(tree, 2000)) {
            assertTrue(chunk.length() <= 2000, label + ": a chunk is at most 2000 characters, found " + chunk.length());
        }
    }

    private static void walk(String label, Node node, int depth, int[] count) {
        count[0]++;
        assertTrue(depth <= MAX_DEPTH, label + ": the tree is at most " + MAX_DEPTH + " deep");
        assertTrue(TYPES.contains(node.type()), label + ": unknown node type " + node.type());
        switch (node) {
            case Node.Link link -> {
                assertSafeHref(label, link.href());
                link.children().forEach(child -> walk(label, child, depth + 1, count));
            }
            case Node.Strong strong -> strong.children().forEach(child -> walk(label, child, depth + 1, count));
            case Node.Em em -> em.children().forEach(child -> walk(label, child, depth + 1, count));
            case Node.Del del -> del.children().forEach(child -> walk(label, child, depth + 1, count));
            case Node.Paragraph paragraph -> paragraph.children().forEach(child -> walk(label, child, depth + 1, count));
            case Node.Quote quote -> quote.children().forEach(child -> walk(label, child, depth + 1, count));
            case Node.Item item -> item.children().forEach(child -> walk(label, child, depth + 1, count));
            case Node.ListNode list -> list.items().forEach(child -> walk(label, child, depth + 1, count));
            case Node.Text ignored -> {
            }
            case Node.Break ignored -> {
            }
            case Node.Code ignored -> {
            }
            case Node.CodeBlock ignored -> {
            }
        }
    }

    static void assertSafeHref(String label, String href) {
        URI uri = assertDoesNotThrow(() -> new URI(href), label + ": a link href is a URI: " + href);
        assertTrue("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()), label + ": only http(s) links, found " + href);
        assertNull(uri.getRawUserInfo(), label + ": no user info in " + href);
        assertNotNull(uri.getHost(), label + ": a host in " + href);
        assertFalse(href.chars().anyMatch(c -> c <= 0x20 || c >= 0x7f || c == '<' || c == '>' || c == '"' || c == '\\'),
                label + ": no forbidden character in " + href);
        assertTrue(href.length() <= 2100, label + ": the href is bounded");
    }
}
