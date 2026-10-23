package net.onelitefeather.blackhole.backend.section;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validation and matching of the backend-server name patterns a SERVER section lists. A pattern is
 * a glob over a server name: {@code *} matches any run of characters, {@code ?} exactly one.
 */
public final class ServerPatterns {

    /** Upper bound on the length of a single pattern. */
    public static final int MAX_PATTERN_LENGTH = 64;

    /** Upper bound on how many patterns one section may list. */
    public static final int MAX_PATTERNS = 50;

    private static final Pattern CLOUDNET_NAME = Pattern.compile("^[A-Za-z0-9_.\\-]+$");

    private static final Pattern ALLOWED_CHARACTERS = Pattern.compile("^[A-Za-z0-9_.\\-*?]+$");

    /**
     * Checks the patterns of a section against its enforcement kind.
     *
     * <ul>
     *     <li>SERVER sections need at least one pattern.</li>
     *     <li>Other kinds must not list any.</li>
     *     <li>A pattern must be a non-blank glob of letters, digits, {@code _ . -} and the wildcards,
     *     and must contain at least one literal character, so it can never match every server
     *     (a BAN section covers the whole network).</li>
     * </ul>
     *
     * @param enforcement the section's enforcement kind
     * @param patterns    the listed patterns, possibly {@code null}
     * @return one message per violated rule, empty if valid
     */
    public static List<String> validate(EnforcementKind enforcement, Collection<String> patterns) {
        return validate(enforcement, ServerTargetKind.PATTERN, patterns);
    }

    /**
     * Checks the entries of a section against its enforcement and target kind. {@link ServerTargetKind#PATTERN}
     * entries follow the glob rules of {@link #validate(EnforcementKind, Collection)}; CloudNet entries are
     * exact names of letters, digits and {@code _ . -} (no wildcards), at most {@value #MAX_PATTERN_LENGTH}
     * characters, at most {@value #MAX_PATTERNS} per section. A name is never checked against CloudNet:
     * the target may legitimately not exist yet.
     *
     * @param enforcement the section's enforcement kind
     * @param targetKind  what the entries mean, {@code null} for {@link ServerTargetKind#PATTERN}
     * @param entries     the listed patterns or CloudNet names, possibly {@code null}
     * @return one message per violated rule, empty if valid
     */
    public static List<String> validate(EnforcementKind enforcement, ServerTargetKind targetKind, Collection<String> entries) {
        ServerTargetKind kind = targetKind == null ? ServerTargetKind.PATTERN : targetKind;
        boolean cloudNet = kind != ServerTargetKind.PATTERN;
        List<String> errors = new ArrayList<>();
        boolean none = entries == null || entries.isEmpty();
        if (enforcement != EnforcementKind.SERVER) {
            if (!none) {
                errors.add("servers are only allowed for SERVER sections");
            }
            if (cloudNet) {
                errors.add("targetKind " + kind + " is only allowed for SERVER sections");
            }
            return errors;
        }
        if (none) {
            errors.add("a SERVER section needs at least one server " + (cloudNet ? "name" : "pattern"));
            return errors;
        }
        if (entries.size() > MAX_PATTERNS) {
            errors.add("a section may list at most " + MAX_PATTERNS + " server " + (cloudNet ? "names" : "patterns"));
        }
        for (String entry : entries) {
            String problem = cloudNet ? problemWithName(entry) : problemWith(entry);
            if (problem != null) {
                errors.add("server " + (cloudNet ? "name" : "pattern") + " '" + entry + "' " + problem);
            }
        }
        return errors;
    }

    private static String problemWithName(String name) {
        if (name == null || name.isBlank()) {
            return "must not be blank";
        }
        if (name.length() > MAX_PATTERN_LENGTH) {
            return "is longer than " + MAX_PATTERN_LENGTH + " characters";
        }
        if (!CLOUDNET_NAME.matcher(name).matches()) {
            return "may only contain letters, digits, '_', '.' and '-' (no wildcards)";
        }
        return null;
    }

    private static String problemWith(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return "must not be blank";
        }
        if (pattern.length() > MAX_PATTERN_LENGTH) {
            return "is longer than " + MAX_PATTERN_LENGTH + " characters";
        }
        if (!ALLOWED_CHARACTERS.matcher(pattern).matches()) {
            return "may only contain letters, digits, '_', '.', '-', '*' and '?'";
        }
        if (pattern.chars().allMatch(c -> c == '*' || c == '?')) {
            return "matches every server; use a BAN section for network-wide punishments";
        }
        return null;
    }

    /**
     * Whether a server name matches the glob, ignoring case (Velocity looks servers up case-insensitively).
     *
     * @param pattern    the glob
     * @param serverName the backend server name
     * @return {@code true} if the whole name matches
     */
    public static boolean matches(String pattern, String serverName) {
        return toRegex(pattern).matcher(serverName).matches();
    }

    private static Pattern toRegex(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (char c : pattern.toLowerCase(Locale.ROOT).toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    private ServerPatterns() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
