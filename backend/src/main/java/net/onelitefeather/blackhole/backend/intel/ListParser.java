package net.onelitefeather.blackhole.backend.intel;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads a list source body: one CIDR per line (the X4BNet format, verified 2026-10-29). Blank lines and lines starting
 * with {@code #} are ignored. Any other line must be a valid CIDR; invalid lines are counted, and a body with more than
 * 5 percent invalid lines, or with no entries at all, fails the source.
 */
final class ListParser {

    /** The most invalid lines a body may have, as a fraction of its entry lines. */
    static final int MAX_INVALID_PERCENT = 5;

    /** @param cidrs the distinct canonical networks, in file order; @param invalid the lines that were not CIDR */
    record Parsed(List<String> cidrs, int invalid) {
    }

    private ListParser() {
    }

    static Parsed parse(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8);
        Set<String> cidrs = new LinkedHashSet<>();
        int invalid = 0;
        int lines = 0;
        for (String raw : text.split("\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            lines++;
            var prefix = IntelPrefix.parseCidr(line);
            if (prefix.isPresent()) {
                cidrs.add(prefix.get().toString());
            } else {
                invalid++;
            }
        }
        if (lines == 0) {
            throw new ListRefreshException("the list has no entries");
        }
        if (invalid * 100L > lines * (long) MAX_INVALID_PERCENT) {
            throw new ListRefreshException("too many invalid lines: " + invalid + " of " + lines);
        }
        return new Parsed(new ArrayList<>(cidrs), invalid);
    }
}
