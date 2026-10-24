package net.onelitefeather.blackhole.markdown;

import org.junit.jupiter.api.Test;

import java.util.Random;

/** Random strings from an alphabet made of the characters that matter, with a fixed seed so a failure repeats. */
class RandomInputTest {

    private static final String[] PIECES = {
            "*", "**", "_", "~~", "~", "`", "```", "[", "]", "(", ")", "<", ">", "!", "\\", "#", "- ", "1. ", "> ", "\n", "\n\n", " ", "  ",
            "a", "b", "x", "h", "http://", "https://", "javascript:", "example.org", "onelitefeather.net", "@", "@everyone", "<@1>", ":", "/",
            "​", "‮", "\t", "\r", "\u0000", "&#106;", "%6A", "😀", "|", "."
    };

    private static String random(Random random, int pieces) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < pieces; i++) {
            out.append(PIECES[random.nextInt(PIECES.length)]);
        }
        return out.toString();
    }

    @Test
    void threeThousandRandomSourcesKeepEveryInvariant() {
        Random random = new Random(20261024L);
        for (int i = 0; i < 3_000; i++) {
            String source = random(random, 1 + random.nextInt(120));
            Invariants.assertSafe("random #" + i + " seed 20261024 source " + escaped(source), source);
        }
    }

    @Test
    void fiftyLongRandomSourcesKeepEveryInvariant() {
        Random random = new Random(7L);
        for (int i = 0; i < 50; i++) {
            String source = random(random, 5_000);
            Invariants.assertSafe("long random #" + i + " seed 7", source);
        }
    }

    private static String escaped(String source) {
        StringBuilder out = new StringBuilder();
        for (char c : source.toCharArray()) {
            out.append(c < 0x20 || c > 0x7e ? String.format("\\u%04x", (int) c) : String.valueOf(c));
        }
        return out.toString();
    }
}
