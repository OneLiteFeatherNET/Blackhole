package net.onelitefeather.blackhole.markdown;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The conformance corpus {@code corpus/conformance.json} is the one specification of behaviour: for every source it names the
 * expected tree, the expected Discord text and the expected plain text. A case flagged {@code roundTrip} also has to parse back
 * from its Discord text to the same tree.
 */
class ConformanceCorpusTest {

    private static final JsonNode CORPUS = CorpusFiles.read("conformance.json");

    @Test
    void theCorpusIsNotEmpty() {
        assertTrue(CORPUS.size() >= 40, "the corpus holds the supported subset, found " + CORPUS.size() + " cases");
    }

    @TestFactory
    Stream<DynamicContainer> cases() {
        List<DynamicContainer> containers = new ArrayList<>();
        for (JsonNode entry : CORPUS) {
            String source = entry.get("source").asText();
            List<Node> tree = new MarkdownParser().parse(source);
            List<DynamicTest> tests = new ArrayList<>();
            tests.add(DynamicTest.dynamicTest("tree", () -> assertEquals(entry.get("tree"), CorpusFiles.MAPPER.valueToTree(Node.toMaps(tree)),
                    "the tree of " + show(source))));
            tests.add(DynamicTest.dynamicTest("discord", () -> assertEquals(entry.get("discord").asText(), DiscordRenderer.render(tree),
                    "the Discord text of " + show(source))));
            tests.add(DynamicTest.dynamicTest("plain", () -> assertEquals(entry.get("plain").asText(), PlainTextRenderer.render(tree),
                    "the plain text of " + show(source))));
            if (entry.get("roundTrip").asBoolean()) {
                tests.add(DynamicTest.dynamicTest("round trip", () -> assertEquals(Node.toMaps(tree),
                        Node.toMaps(new MarkdownParser().parse(DiscordRenderer.render(tree))), "source to Discord to source for " + show(source))));
            }
            containers.add(DynamicContainer.dynamicContainer(entry.get("name").asText(), tests));
        }
        return containers.stream();
    }

    @TestFactory
    Stream<DynamicTest> everyCaseIsAlsoSafe() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode entry : CORPUS) {
            tests.add(DynamicTest.dynamicTest(entry.get("name").asText(), () -> Invariants.assertSafe(entry.get("name").asText(), entry.get("source").asText())));
        }
        return tests.stream();
    }

    private static String show(String source) {
        return "'" + source.replace("\n", "\\n").replace("\r", "\\r") + "'";
    }
}
