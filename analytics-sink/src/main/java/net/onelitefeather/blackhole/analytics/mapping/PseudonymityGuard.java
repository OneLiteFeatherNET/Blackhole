package net.onelitefeather.blackhole.analytics.mapping;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Refuses a payload that would put a raw player UUID or an IP address into a pseudonymous table.
 *
 * <p>A UUID is allowed only under an identifier key ({@code ...Identifier}, {@code eventId}): those are entity ids, not
 * player identities. A UUID under any other key (for example {@code owner}) is treated as a raw player UUID. An IP
 * address is refused under any key.</p>
 */
final class PseudonymityGuard {

    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern IPV4 = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)$");
    private static final Pattern IPV6_FULL = Pattern.compile("^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$");
    private static final Pattern IPV6_COMPRESSED = Pattern.compile("^[0-9a-fA-F:]*::[0-9a-fA-F:]*$");

    /**
     * @return the offending key and kind of value, or empty when the payload is pseudonymous
     */
    static Optional<String> check(Map<String, Object> payload) {
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            Optional<String> violation = checkValue(entry.getKey(), entry.getValue());
            if (violation.isPresent()) {
                return violation;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> checkValue(String key, Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> child : map.entrySet()) {
                Optional<String> violation = checkValue(String.valueOf(child.getKey()), child.getValue());
                if (violation.isPresent()) {
                    return violation;
                }
            }
            return Optional.empty();
        }
        if (value instanceof Collection<?> items) {
            for (Object item : items) {
                Optional<String> violation = checkValue(key, item);
                if (violation.isPresent()) {
                    return violation;
                }
            }
            return Optional.empty();
        }
        if (value instanceof String text) {
            if (isIpAddress(text)) {
                return Optional.of("ip address under key " + key);
            }
            if (UUID.matcher(text).matches() && !isIdentifierKey(key)) {
                return Optional.of("uuid under key " + key);
            }
        }
        return Optional.empty();
    }

    private static boolean isIpAddress(String text) {
        return IPV4.matcher(text).matches()
                || IPV6_FULL.matcher(text).matches()
                || IPV6_COMPRESSED.matcher(text).matches();
    }

    /**
     * Entity identifier keys: {@code ...Identifier}, {@code ...Id} and {@code eventId}. A key that names a player, a
     * UUID or an owner is never an identifier key, even when it ends like one.
     */
    private static boolean isIdentifierKey(String key) {
        if ("eventId".equals(key)) {
            return true;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.contains("player") || lower.contains("uuid") || lower.contains("owner")) {
            return false;
        }
        return key.endsWith("Identifier") || key.endsWith("Id");
    }

    private PseudonymityGuard() {
    }
}
