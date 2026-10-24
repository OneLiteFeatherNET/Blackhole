package net.onelitefeather.blackhole.markdown;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hostile corpus: dangerous and encoded schemes, HTML, data URIs, nested and unbalanced markup, deep nesting, adversarial and
 * very long inputs, control, bidirectional and zero-width characters and Discord mentions. Every case must produce no non-http(s)
 * link, no throw, bounded output and a bounded number of steps (see {@link Invariants}).
 */
class HostileCorpusTest {

    private static final JsonNode CORPUS = CorpusFiles.read("hostile.json");

    @Test
    void theCorpusCoversTheCasesTheDesignNames() {
        assertTrue(CORPUS.size() >= 120, "the corpus is not empty, found " + CORPUS.size());
    }

    @TestFactory
    Stream<DynamicTest> corpusFile() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode entry : CORPUS) {
            tests.add(DynamicTest.dynamicTest(entry.get("name").asText(), () -> Invariants.assertSafe(entry.get("name").asText(), entry.get("source").asText())));
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> longAndAdversarialInputs() {
        List<DynamicTest> tests = new ArrayList<>();
        generated().forEach((name, source) -> tests.add(DynamicTest.dynamicTest(name, () -> Invariants.assertSafe(name, source))));
        return tests.stream();
    }

    private static java.util.Map<String, String> generated() {
        java.util.Map<String, String> cases = new java.util.LinkedHashMap<>();
        cases.put("100000 brackets, stars, underscores and backticks", "[*_`".repeat(25_000));
        cases.put("100000 open brackets", "[".repeat(100_000));
        cases.put("100000 close bracket and paren", "](".repeat(50_000));
        cases.put("100000 stars", "*".repeat(100_000));
        cases.put("100000 underscores", "_".repeat(100_000));
        cases.put("100000 tildes", "~".repeat(100_000));
        cases.put("100000 backticks", "`".repeat(100_000));
        StringBuilder runs = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            runs.append("`".repeat(i % 7 + 1)).append('a');
        }
        cases.put("backtick runs of changing length", runs.toString());
        cases.put("stars before words", "*a ".repeat(33_333));
        cases.put("stars after words", "a* ".repeat(33_333));
        cases.put("link openers", "[a](".repeat(25_000));
        cases.put("link openers with a scheme", "[a](https://".repeat(8_000));
        cases.put("100000 angle brackets", "<".repeat(100_000));
        cases.put("angle brackets with a scheme", "<https://a".repeat(9_000));
        cases.put("bare schemes", "https://".repeat(12_500));
        cases.put("scheme prefix without a host", "http".repeat(25_000));
        cases.put("100000 quote markers", ">".repeat(100_000));
        cases.put("spaced quote markers", "> ".repeat(50_000));
        cases.put("spaced list markers", "- ".repeat(50_000));
        cases.put("quote lines", "> a\n".repeat(25_000));
        cases.put("100000 backslashes", "\\".repeat(100_000));
        cases.put("bang and bracket", "![".repeat(33_333));
        cases.put("one very long line", "word ".repeat(20_000));
        cases.put("emphasis openers of every kind", "*a _b ~~c **d ".repeat(5_000));
        cases.put("emphasis closers of every kind", "d** c~~ b_ a* ".repeat(5_000));
        cases.put("image openers", "![a](".repeat(20_000));
        cases.put("only line feeds", "\n".repeat(5_000));
        cases.put("only spaces", " ".repeat(5_000));
        cases.put("blank lines between list items", "- a" + "\n".repeat(3_000) + "- b");
        cases.put("many paragraphs", "a\n\n".repeat(3_000));
        cases.put("many list items", "- a\n".repeat(3_000));
        cases.put("escapes", "\\*".repeat(3_000));
        cases.put("deep list nesting", deepList(60));
        cases.put("50 quotes deep", "> ".repeat(50) + "x");
        return cases;
    }

    private static String deepList(int depth) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            out.append("  ".repeat(i)).append("- x\n");
        }
        return out.toString();
    }

    @Test
    void aHundredThousandBracketsStayWithinTheNodeLimitAndTheStepBudget() {
        String source = "[*_`".repeat(25_000);
        MarkdownParser parser = new MarkdownParser();
        List<Node> tree = parser.parse(source);
        assertTrue(parser.steps() <= 40L * source.length(), "linear work, counted: " + parser.steps() + " steps");
        assertFalse(tree.isEmpty(), "something is returned");
        assertTrue(Node.toMaps(tree).toString().length() < 4 * source.length(), "the tree is bounded");
    }

    @Test
    void aDangerousLinkIsNeverALinkNode() {
        for (String target : List.of("javascript:alert(1)", "JaVaScRiPt:alert(1)", "data:text/html;base64,AAAA", "vbscript:x", "file:///etc/passwd", "//evil.example")) {
            List<Node> tree = new MarkdownParser().parse("[x](" + target + ")");
            assertEquals(1, tree.size(), "one paragraph for " + target);
            assertEquals(List.of(new Node.Text("[x](" + target + ")")), ((Node.Paragraph) tree.getFirst()).children(),
                    "the source of " + target + " stays literal text");
        }
    }
}
