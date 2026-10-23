package net.onelitefeather.blackhole.backend.evidence.validation;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps raw player UUIDs and IP addresses out of evidence. Structured payloads are rejected when a
 * string looks like either (callers hash first - rewriting a UUID would silently guess that it is a
 * player's); free chat text is redacted instead, because players do type IP addresses there and
 * rejecting would drop the evidence.
 *
 * <p>Everything here is a pure, local text check: no address is ever resolved.
 */
public final class PiiGuard {

    /** What replaces an IP address found in chat text. */
    public static final String IP_PLACEHOLDER = "[ip]";

    private static final Pattern UUID = Pattern.compile(
            "(?<![0-9a-fA-F])[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?![0-9a-fA-F])");
    private static final Pattern IPV4 = Pattern.compile("(?<![\\d.])\\d{1,3}(?:\\.\\d{1,3}){3}(?!\\d|\\.\\d)");
    private static final Pattern IPV6_CANDIDATE = Pattern.compile("[0-9A-Fa-f:.]{3,}");
    private static final Pattern HEX_GROUP = Pattern.compile("[0-9A-Fa-f]{1,4}");

    private static final Pattern BAN_ID_SHAPE = Pattern.compile("#\\s?([0-9A-Za-z]{8})(?![0-9A-Za-z])");
    private static final Pattern HEX_ONLY = Pattern.compile("[0-9A-F]{8}");

    /**
     * The normalised Ban ID shaped tokens of a text: {@code #} and eight alphabet characters, read the
     * way a player may type them. A hex-only token can also be a legacy short id, which staff do name
     * in notes, so the caller decides those by lookup; see {@link #isHexOnly}.
     */
    public static List<String> banIdCandidates(String text) {
        List<String> found = new java.util.ArrayList<>();
        if (text == null) {
            return found;
        }
        Matcher matcher = BAN_ID_SHAPE.matcher(text);
        while (matcher.find()) {
            net.onelitefeather.blackhole.backend.banid.BanIdCodec.normalize(matcher.group(1)).ifPresent(found::add);
        }
        return found;
    }

    /** Whether a normalised candidate is also a legacy short id (eight hex digits). */
    public static boolean isHexOnly(String normalizedCandidate) {
        return HEX_ONLY.matcher(normalizedCandidate).matches();
    }

    /** Whether the text contains a canonical (dashed) UUID. */
    public static boolean containsUuid(String text) {
        return text != null && UUID.matcher(text).find();
    }

    /** Whether the text contains an IPv4 or IPv6 address literal. */
    public static boolean containsIp(String text) {
        return text != null && !redactIps(text).equals(text);
    }

    /** Replaces every IPv4 and IPv6 address literal in the text with {@value #IP_PLACEHOLDER}. */
    public static String redactIps(String text) {
        if (text == null) {
            return null;
        }
        String withoutV6 = replaceIpv6(text);
        Matcher matcher = IPV4.matcher(withoutV6);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(isIpv4(matcher.group()) ? IP_PLACEHOLDER : matcher.group()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String replaceIpv6(String text) {
        Matcher matcher = IPV6_CANDIDATE.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group();
            // A sentence-ending dot is punctuation, not part of the address.
            String address = token.replaceAll("\\.+$", "");
            String trailing = token.substring(address.length());
            matcher.appendReplacement(result, Matcher.quoteReplacement(isIpv6(address) ? IP_PLACEHOLDER + trailing : token));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static boolean isIpv4(String token) {
        String[] octets = token.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3 || Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIpv6(String token) {
        if (token.chars().filter(c -> c == ':').count() < 2) {
            return false;
        }
        int compression = token.indexOf("::");
        if (compression != token.lastIndexOf("::") || token.contains(":::")) {
            return false;
        }
        String left = compression < 0 ? token : token.substring(0, compression);
        String right = compression < 0 ? "" : token.substring(compression + 2);
        Integer leftGroups = countGroups(left, compression < 0);
        Integer rightGroups = countGroups(right, true);
        if (leftGroups == null || rightGroups == null) {
            return false;
        }
        int total = leftGroups + rightGroups;
        if (compression < 0) {
            return total == 8;
        }
        // "::" stands for at least one group of zeros; a bare "::" carries no hex digit and is no address.
        return total <= 7 && token.chars().anyMatch(c -> Character.digit(c, 16) >= 0);
    }

    /**
     * Number of 16-bit groups in a colon-separated run, or {@code null} if it is not valid.
     * A trailing dotted IPv4 counts as two groups, and only the last run may end in one.
     */
    private static Integer countGroups(String run, boolean mayEndInIpv4) {
        if (run.isEmpty()) {
            return 0;
        }
        if (run.startsWith(":") || run.endsWith(":")) {
            return null;
        }
        String[] groups = run.split(":", -1);
        int count = 0;
        for (int i = 0; i < groups.length; i++) {
            String group = groups[i];
            boolean last = i == groups.length - 1;
            if (group.contains(".")) {
                if (!last || !mayEndInIpv4 || !isIpv4(group)) {
                    return null;
                }
                count += 2;
            } else if (HEX_GROUP.matcher(group).matches()) {
                count++;
            } else {
                return null;
            }
        }
        return count;
    }

    /**
     * Finds the first string in a structured value that looks like a UUID or an IP address.
     *
     * @param pointer the JSON pointer of {@code value}, e.g. {@code /values/ip}
     * @param value   a string, or maps and lists of values
     * @return the JSON pointer of the offending string, empty if there is none
     */
    public static Optional<String> firstViolation(String pointer, Object value) {
        if (value instanceof String text) {
            return containsUuid(text) || containsIp(text) ? Optional.of(pointer.isEmpty() ? "/" : pointer) : Optional.empty();
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Optional<String> inKey = firstViolation(pointer + "/" + escape(key), key);
                if (inKey.isPresent()) {
                    return inKey;
                }
                Optional<String> found = firstViolation(pointer + "/" + escape(key), entry.getValue());
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                Optional<String> found = firstViolation(pointer + "/" + i, list.get(i));
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    private static String escape(String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }

    private PiiGuard() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
