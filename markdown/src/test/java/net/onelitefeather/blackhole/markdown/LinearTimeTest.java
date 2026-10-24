package net.onelitefeather.blackhole.markdown;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Linear time asserted with the parser's own step counter, never a clock: ten times the input may cost at most about ten times
 * the steps, and every step count stays under a fixed multiple of the input.
 */
class LinearTimeTest {

    private static Map<String, IntFunction<String>> patterns() {
        Map<String, IntFunction<String>> patterns = new LinkedHashMap<>();
        patterns.put("brackets, stars, underscores and backticks", n -> "[*_`".repeat(n / 4));
        patterns.put("open brackets", n -> "[".repeat(n));
        patterns.put("link openers", n -> "[a](".repeat(n / 4));
        patterns.put("close bracket and paren", n -> "](".repeat(n / 2));
        patterns.put("stars", n -> "*".repeat(n));
        patterns.put("backtick runs of changing length", n -> {
            StringBuilder out = new StringBuilder();
            for (int i = 0; out.length() < n; i++) {
                out.append("`".repeat(i % 9 + 1)).append('a');
            }
            return out.toString();
        });
        patterns.put("emphasis openers of every kind", n -> "*a _b ~~c **d ".repeat(n / 14));
        patterns.put("emphasis closers of every kind", n -> "d** c~~ b_ a* ".repeat(n / 14));
        patterns.put("angle brackets with a scheme", n -> "<https://a".repeat(n / 10));
        patterns.put("bare schemes", n -> "https://".repeat(n / 8));
        patterns.put("nested quote markers", n -> "> ".repeat(n / 2));
        patterns.put("list markers", n -> "- ".repeat(n / 2));
        patterns.put("blank lines", n -> "- a" + "\n".repeat(n) + "- b");
        patterns.put("many lines", n -> "- a\n  b\n".repeat(n / 8));
        patterns.put("image openers", n -> "![a](".repeat(n / 5));
        return patterns;
    }

    private static long steps(String source) {
        MarkdownParser parser = new MarkdownParser();
        parser.parse(source);
        return parser.steps();
    }

    @TestFactory
    Stream<DynamicTest> stepsGrowLinearlyWithTheInput() {
        return patterns().entrySet().stream().map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
            String small = entry.getValue().apply(10_000);
            String large = entry.getValue().apply(100_000);
            long smallSteps = steps(small);
            long largeSteps = steps(large);
            assertTrue(largeSteps <= 40L * large.length() + 1_000, entry.getKey() + ": " + largeSteps + " steps for " + large.length() + " characters");
            assertTrue(largeSteps <= 12 * smallSteps + 1_000, entry.getKey() + ": ten times the input cost " + largeSteps + " steps against "
                    + smallSteps + " (more than about ten times)");
        }));
    }
}
