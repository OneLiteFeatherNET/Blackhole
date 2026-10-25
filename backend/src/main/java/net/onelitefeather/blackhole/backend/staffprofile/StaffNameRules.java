package net.onelitefeather.blackhole.backend.staffprofile;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules of a staff display name, as pure text checks: no clock, no network, no storage.
 *
 * <p>The name is NFKC-normalised and trimmed, runs of whitespace collapse to one space, and it is 2 to 32
 * characters. Only letters, digits, space, hyphen, underscore, full stop and apostrophe are allowed, which
 * keeps markup out of chat. A URL, an IP address, a UUID or a Ban ID lookalike is refused, and so is every
 * reserved word, as a whole name or as one of its words, compared case-insensitively.</p>
 */
public final class StaffNameRules {

    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 32;

    /** Why a name was refused. */
    public enum Problem {
        LENGTH, CHARACTERS, LOOKALIKE, RESERVED
    }

    /** The outcome of {@link #check}. */
    public sealed interface Verdict permits Accepted, Rejected {
    }

    /**
     * An acceptable name.
     *
     * @param displayName the trimmed, normalised name as shown to players
     * @param folded      the name folded for the uniqueness check
     */
    public record Accepted(String displayName, String folded) implements Verdict {
    }

    /** A refused name and the rule it broke. */
    public record Rejected(Problem problem) implements Verdict {
    }

    private static final Pattern ALLOWED = Pattern.compile("[\\p{L}\\p{M}\\p{N} '’._-]+");
    private static final Pattern URL = Pattern.compile(
            "(?i)(://|www\\.|\\b[a-z0-9-]+\\.(com|net|org|de|gg|io|dev|eu|info|me|xyz|co|uk|tv)\\b)");
    private static final Pattern IPV4 = Pattern.compile("(?<![\\d.])\\d{1,3}(\\.\\d{1,3}){3}(?![\\d.])");
    private static final Pattern UUID = Pattern.compile(
            "(?i)[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}");
    private static final Pattern BAN_ID_SHAPE = Pattern.compile("[A-Z0-9]{8}");
    private static final Pattern WORD_SEPARATOR = Pattern.compile("[\\s._'’-]+");

    private static final Set<String> RESERVED = Set.of(
            "staff", "team", "moderation", "moderationsteam", "moderator", "admin", "administrator",
            "system", "bot", "support", "discord", "verwaltung");

    private StaffNameRules() {
    }

    /** Checks a candidate display name. A {@code null} name is too short. */
    public static Verdict check(String raw) {
        if (raw == null) {
            return new Rejected(Problem.LENGTH);
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ");
        // Identifiers and links are named as such first: a URL or a UUID is a lookalike whatever its length or characters.
        if (looksLikeAnIdentifier(normalized)) {
            return new Rejected(Problem.LOOKALIKE);
        }
        if (!ALLOWED.matcher(normalized).matches()) {
            return new Rejected(Problem.CHARACTERS);
        }
        int length = normalized.codePointCount(0, normalized.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            return new Rejected(Problem.LENGTH);
        }
        String folded = fold(normalized);
        if (isReserved(folded)) {
            return new Rejected(Problem.RESERVED);
        }
        return new Accepted(normalized, folded);
    }

    /** The comparison form of a name: NFKC, then lower case. Two names with the same fold are the same name. */
    public static String fold(String displayName) {
        return Normalizer.normalize(displayName, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static boolean looksLikeAnIdentifier(String name) {
        if (URL.matcher(name).find() || IPV4.matcher(name).find() || UUID.matcher(name).find()) {
            return true;
        }
        for (String word : WORD_SEPARATOR.split(name)) {
            if (isBanIdShape(word)) {
                return true;
            }
        }
        return false;
    }

    /** Eight upper-case alphanumerics with a letter and a digit: the shape of a Ban ID. */
    private static boolean isBanIdShape(String word) {
        if (!BAN_ID_SHAPE.matcher(word).matches()) {
            return false;
        }
        boolean letter = word.chars().anyMatch(Character::isLetter);
        boolean digit = word.chars().anyMatch(Character::isDigit);
        return letter && digit;
    }

    private static boolean isReserved(String folded) {
        if (RESERVED.contains(folded)) {
            return true;
        }
        for (String word : WORD_SEPARATOR.split(folded)) {
            if (RESERVED.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
