package net.onelitefeather.blackhole.velocity.evidence;

import org.jetbrains.annotations.NotNull;

import java.util.regex.Pattern;

/**
 * Removes what must not leave the proxy from a line of chat: URLs, IP addresses and UUIDs. The
 * backend redacts IP addresses as well, but the proxy does it first so they never travel over the wire.
 * Names players type into their text cannot be recognised and are kept.
 */
public final class ChatRedactor {

    private static final String TLDS = "com|net|org|de|eu|io|gg|me|tv|xyz|info|co|us|uk|ru|fr|nl|pl|tk|ly|to|cc|link|club|online|site|app|dev";

    /** {@code http(s)://...}, {@code www....} and bare domains with a common top-level domain. */
    private static final Pattern URL = Pattern.compile(
            "(?i)\\b(?:[a-z][a-z0-9+.-]*://|www\\.)\\S+|\\b(?:[a-z0-9-]+\\.)+(?:" + TLDS + ")\\b(?::\\d{1,5})?(?:/\\S*)?");

    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b(?::\\d{1,5})?");

    /** The full eight-group form or the compressed form with {@code ::}. */
    private static final Pattern IPV6 = Pattern.compile(
            "(?i)(?<![\\w:])(?:(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}"
                    + "|(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,6})?::(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,6})?)(?![\\w:])");

    private static final Pattern UUID = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");

    private ChatRedactor() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * @return the text with URLs replaced by {@code [url]}, IP addresses by {@code [ip]} and UUIDs by {@code [uuid]}
     */
    public static @NotNull String redact(@NotNull String text) {
        String redacted = URL.matcher(text).replaceAll("[url]");
        redacted = IPV6.matcher(redacted).replaceAll("[ip]");
        redacted = IPV4.matcher(redacted).replaceAll("[ip]");
        return UUID.matcher(redacted).replaceAll("[uuid]");
    }
}
