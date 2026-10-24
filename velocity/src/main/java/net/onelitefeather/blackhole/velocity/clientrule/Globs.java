package net.onelitefeather.blackhole.velocity.clientrule;

import java.util.Collection;
import java.util.Locale;

/**
 * Case-insensitive glob matching for client rule patterns: {@code *} matches any run of characters,
 * {@code ?} exactly one. It is a plain two-pointer scan, never a regular expression, so a pattern with
 * many stars cannot blow up on a long value (worst case is the product of both lengths).
 */
public final class Globs {

    private Globs() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /** @return whether the whole value matches the pattern */
    public static boolean matches(String pattern, String value) {
        String glob = pattern.toLowerCase(Locale.ROOT);
        String text = value.toLowerCase(Locale.ROOT);
        int p = 0;
        int t = 0;
        int star = -1;
        int resume = 0;
        while (t < text.length()) {
            if (p < glob.length() && glob.charAt(p) == '*') {
                star = p++;
                resume = t;
            } else if (p < glob.length() && (glob.charAt(p) == '?' || glob.charAt(p) == text.charAt(t))) {
                p++;
                t++;
            } else if (star >= 0) {
                p = star + 1;
                t = ++resume;
            } else {
                return false;
            }
        }
        while (p < glob.length() && glob.charAt(p) == '*') {
            p++;
        }
        return p == glob.length();
    }

    /** @return whether any pattern matches the value */
    public static boolean matchesAny(Collection<String> patterns, String value) {
        for (String pattern : patterns) {
            if (matches(pattern, value)) {
                return true;
            }
        }
        return false;
    }
}
