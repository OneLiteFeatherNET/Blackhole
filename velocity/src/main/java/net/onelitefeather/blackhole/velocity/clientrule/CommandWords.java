package net.onelitefeather.blackhole.velocity.clientrule;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The words of a command line a command rule is matched against: the first word only, after removing
 * leading spaces and slashes and lower-casing. A word of the form {@code namespace:label} is matched as
 * written and as {@code label}, so {@code minecraft:op} cannot slip past a rule for {@code op}. Arguments
 * are never looked at.
 */
public final class CommandWords {

    private CommandWords() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /** @return the words to match, empty for a blank line */
    public static @NotNull Set<String> candidates(@NotNull String line) {
        int start = 0;
        while (start < line.length() && (line.charAt(start) == '/' || Character.isWhitespace(line.charAt(start)))) {
            start++;
        }
        int end = start;
        while (end < line.length() && !Character.isWhitespace(line.charAt(end))) {
            end++;
        }
        String word = line.substring(start, end).toLowerCase(Locale.ROOT);
        Set<String> words = new LinkedHashSet<>();
        if (word.isEmpty()) {
            return words;
        }
        words.add(word);
        int colon = word.indexOf(':');
        if (colon >= 0 && colon < word.length() - 1) {
            words.add(word.substring(colon + 1));
        }
        return words;
    }
}
