package net.onelitefeather.blackhole.markdown;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownParserTest {

    private final MarkdownParser parser = new MarkdownParser();

    private static int count(Node node) {
        return 1 + switch (node) {
            case Node.Strong n -> n.children().stream().mapToInt(MarkdownParserTest::count).sum();
            case Node.Em n -> n.children().stream().mapToInt(MarkdownParserTest::count).sum();
            case Node.Del n -> n.children().stream().mapToInt(MarkdownParserTest::count).sum();
            case Node.Link n -> n.children().stream().mapToInt(MarkdownParserTest::count).sum();
            case Node.Paragraph n -> n.children().stream().mapToInt(MarkdownParserTest::count).sum();
            case Node.Quote n -> n.children().stream().mapToInt(MarkdownParserTest::count).sum();
            case Node.Item n -> n.children().stream().mapToInt(MarkdownParserTest::count).sum();
            case Node.ListNode n -> n.items().stream().mapToInt(MarkdownParserTest::count).sum();
            default -> 0;
        };
    }

    @Test
    void nullAndEmptySourcesGiveAnEmptyTree() {
        assertEquals(List.of(), this.parser.parse(null), "null is nothing");
        assertEquals(List.of(), this.parser.parse(""), "empty is nothing");
    }

    @Test
    void moreThanFiveHundredNodesTurnTheRestIntoOneLiteralParagraph() {
        String source = "a\n\n".repeat(400);
        List<Node> tree = this.parser.parse(source);
        int nodes = tree.stream().mapToInt(MarkdownParserTest::count).sum();
        assertTrue(nodes <= MarkdownParser.MAX_NODES + 2, "the limit holds, found " + nodes);
        Node last = tree.getLast();
        Node.Paragraph rest = assertInstanceOf(Node.Paragraph.class, last, "the remainder is a paragraph");
        assertEquals(1, rest.children().size(), "the remainder is one text");
        Node.Text text = assertInstanceOf(Node.Text.class, rest.children().getFirst(), "the remainder is literal text");
        assertTrue(text.text().contains("a\n\na"), "the remainder keeps the source as it was written");
    }

    @Test
    void theNodeLimitDoesNotCutAMessageThatFits() {
        List<Node> tree = this.parser.parse("a\n\n".repeat(100));
        assertEquals(100, tree.size(), "100 paragraphs of two nodes each fit");
    }

    @Test
    void quotesDeeperThanThreeLevelsAreLiteralText() {
        List<Node> tree = this.parser.parse("> ".repeat(50) + "x");
        Node.Quote one = assertInstanceOf(Node.Quote.class, tree.getFirst(), "level 1");
        Node.Quote two = assertInstanceOf(Node.Quote.class, one.children().getFirst(), "level 2");
        Node.Quote three = assertInstanceOf(Node.Quote.class, two.children().getFirst(), "level 3");
        Node.Paragraph rest = assertInstanceOf(Node.Paragraph.class, three.children().getFirst(), "the rest is a paragraph");
        Node.Text text = assertInstanceOf(Node.Text.class, rest.children().getFirst(), "the rest is text");
        assertEquals("> ".repeat(47) + "x", text.text(), "the remaining markers are shown as written");
    }

    @Test
    void controlAndBidirectionalOverrideCharactersAreDropped() {
        List<Node> tree = this.parser.parse("a\u0000b\u0007c‮d⁦e\u007ff\u0085g");
        assertEquals(List.of(new Node.Paragraph(List.of(new Node.Text("abcdefg")))), tree, "only printable text is left");
    }

    @Test
    void zeroWidthCharactersStayBecauseTheyAreText() {
        List<Node> tree = this.parser.parse("a​b");
        assertEquals(List.of(new Node.Paragraph(List.of(new Node.Text("a​b")))), tree, "the zero-width space is kept");
    }

    @Test
    void aLinkWithUserInfoStaysLiteral() {
        List<Node> tree = this.parser.parse("[x](https://onelitefeather.net@evil.example/)");
        assertEquals(List.of(new Node.Paragraph(List.of(new Node.Text("[x](https://onelitefeather.net@evil.example/)")))), tree,
                "user info makes the whole link source literal");
    }

    @Test
    void trustedDomainsComeFromTheRulesPassedIn() {
        MarkdownParser custom = new MarkdownParser(new LinkRules(List.of("example.org")));
        Node.Paragraph paragraph = (Node.Paragraph) custom.parse("[a](https://sub.example.org/) [b](https://onelitefeather.net/)").getFirst();
        assertEquals(false, ((Node.Link) paragraph.children().get(0)).external(), "the configured domain is trusted");
        assertEquals(true, ((Node.Link) paragraph.children().get(2)).external(), "the default domain is not, when it is not configured");
    }

    @Test
    void theStepCounterStartsAgainForEveryParse() {
        this.parser.parse("a".repeat(1_000));
        long first = this.parser.steps();
        this.parser.parse("a".repeat(1_000));
        assertEquals(first, this.parser.steps(), "the same input costs the same steps");
    }

    @Test
    void aLinkNeverNestsInsideALink() {
        Node.Paragraph paragraph = (Node.Paragraph) this.parser.parse("[a [b](https://b.example/) c](https://c.example/)").getFirst();
        for (Node child : paragraph.children()) {
            if (child instanceof Node.Link link) {
                assertTrue(link.children().stream().noneMatch(Node.Link.class::isInstance), "no link inside a link");
            }
        }
    }
}
