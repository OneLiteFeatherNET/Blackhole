package net.onelitefeather.blackhole.velocity.command;

import net.onelitefeather.blackhole.client.model.KickScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * Reads what follows the player in {@code /kick <player> [server|section] <reason>}, and the target of
 * {@code /kickall}. Free of the command framework and the proxy so it can be tested with plain strings.
 *
 * <p>A bare reason kicks from the network. {@code server <name> <reason>} and {@code section <key>
 * <reason>} kick from one server or section. A reason that itself starts with the word "server" or
 * "section" therefore needs another first word.</p>
 */
public final class KickArguments {

    /** The longest reason the backend accepts. */
    public static final int MAX_REASON = 200;

    /** What the arguments mean, or why they do not. */
    public sealed interface Result permits Parsed, Invalid {
    }

    /**
     * @param target the server name or section key, null for {@code NETWORK}
     */
    public record Parsed(@NotNull KickScope scope, @Nullable String target, @NotNull String reason) implements Result {
    }

    /**
     * @param message what to tell the staff member
     */
    public record Invalid(@NotNull String message) implements Result {
    }

    private KickArguments() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * @param input everything after the player's name
     */
    public static @NotNull Result parse(@NotNull String input) {
        String text = input.strip();
        if (text.isEmpty()) {
            return new Invalid("Give a reason: /kick <player> [server|section] <reason>");
        }
        KickScope scope = scopeOf(firstWord(text));
        if (scope == null) {
            return reasoned(KickScope.NETWORK, null, text);
        }
        String afterKeyword = rest(text);
        if (afterKeyword.isEmpty()) {
            return new Invalid("Name the " + scope.getValue().toLowerCase(Locale.ROOT) + " to kick from.");
        }
        return reasoned(scope, firstWord(afterKeyword), rest(afterKeyword));
    }

    /**
     * @param target        what was typed for the target, optionally prefixed {@code server:} or {@code section:}
     * @param sectionExists whether a bare name is the key of a known section (otherwise it is a server)
     */
    public static @NotNull Result parseMass(@NotNull String target, @NotNull String reason, @NotNull Predicate<String> sectionExists) {
        String name = target.strip();
        KickScope scope = null;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("server:")) {
            scope = KickScope.SERVER;
            name = name.substring("server:".length());
        } else if (lower.startsWith("section:")) {
            scope = KickScope.SECTION;
            name = name.substring("section:".length());
        }
        if (name.isBlank()) {
            return new Invalid("Name the server or section to kick everybody from.");
        }
        if (scope == null) {
            scope = sectionExists.test(name) ? KickScope.SECTION : KickScope.SERVER;
        }
        return reasoned(scope, name, reason.strip());
    }

    private static Result reasoned(KickScope scope, @Nullable String target, String reason) {
        if (reason.isEmpty()) {
            return new Invalid("Give a reason for the kick.");
        }
        if (reason.length() > MAX_REASON) {
            return new Invalid("The reason is too long: " + reason.length() + " of " + MAX_REASON + " characters.");
        }
        return new Parsed(scope, target, reason);
    }

    private static @Nullable KickScope scopeOf(String word) {
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "server" -> KickScope.SERVER;
            case "section" -> KickScope.SECTION;
            default -> null;
        };
    }

    private static String firstWord(String text) {
        int end = 0;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        return text.substring(0, end);
    }

    /** What follows the first word, without the blanks in between. */
    private static String rest(String text) {
        return text.substring(firstWord(text).length()).strip();
    }
}
