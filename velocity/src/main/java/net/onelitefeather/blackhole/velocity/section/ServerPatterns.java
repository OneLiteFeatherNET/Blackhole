package net.onelitefeather.blackhole.velocity.section;

import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Matching of backend server names against the glob patterns of a SERVER section. Mirrors the
 * backend's {@code ServerPatterns.matches}: {@code *} matches any run of characters, {@code ?}
 * exactly one, case-insensitively.
 */
public final class ServerPatterns {

    private ServerPatterns() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * @param pattern    the glob
     * @param serverName the backend server name
     * @return {@code true} if the whole name matches
     */
    public static boolean matches(String pattern, String serverName) {
        StringBuilder regex = new StringBuilder();
        for (char c : pattern.toLowerCase(Locale.ROOT).toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE).matcher(serverName).matches();
    }

    /**
     * @return {@code true} if any of the patterns matches the server name
     */
    public static boolean matchesAny(Collection<String> patterns, String serverName) {
        return patterns.stream().anyMatch(pattern -> matches(pattern, serverName));
    }
}
