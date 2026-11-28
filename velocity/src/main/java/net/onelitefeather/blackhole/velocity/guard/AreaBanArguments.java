package net.onelitefeather.blackhole.velocity.guard;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads what follows {@code /areaban}: {@code asn <number> [duration] <reason>}, {@code country <ISO> [duration]
 * <reason>} and {@code lift asn|country <value> <reason>}. Free of the command framework and the proxy, so it
 * can be tested with plain strings. The backend still decides who may ban what for how long.
 */
public final class AreaBanArguments {

    /** The longest reason the backend accepts. */
    public static final int MAX_REASON = 200;

    static final String USAGE = "Usage: /areaban asn <number> [duration] <reason> | country <ISO> [duration] <reason>"
            + " | lift asn|country <value> <reason>";

    private static final String COUNTRY_PERMISSION = "blackhole.areaban.country";
    private static final Pattern ASN = Pattern.compile("(?i)(?:AS)?(\\d{1,10})");
    private static final Pattern COUNTRY = Pattern.compile("[A-Za-z]{2}");
    private static final Pattern DURATION = Pattern.compile("(\\d{1,9})([smhd])", Pattern.CASE_INSENSITIVE);

    /** The kind of area. */
    public enum Kind { ASN, COUNTRY }

    /** What the arguments mean, or why they do not. */
    public sealed interface Result permits Ban, Lift, Invalid {
    }

    /**
     * @param value           the ASN as digits, or the uppercase country code
     * @param durationSeconds the length of the ban, or null for a permanent one
     * @param reason          why the ban is made
     */
    public record Ban(@NotNull Kind kind, @NotNull String value, @Nullable Long durationSeconds, @NotNull String reason) implements Result {
    }

    /**
     * @param value  the ASN as digits, or the uppercase country code
     * @param reason why the ban is lifted
     */
    public record Lift(@NotNull Kind kind, @NotNull String value, @NotNull String reason) implements Result {
    }

    /**
     * @param message what to tell the staff member
     */
    public record Invalid(@NotNull String message) implements Result {
    }

    private AreaBanArguments() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * @param input   everything after {@code /areaban}
     * @param granted whether the sender holds a permission node; a country ban needs {@value #COUNTRY_PERMISSION}
     */
    public static @NotNull Result parse(@NotNull String input, @NotNull Predicate<String> granted) {
        String text = input.strip();
        if (text.isEmpty()) {
            return new Invalid(USAGE);
        }
        if (firstWord(text).equalsIgnoreCase("lift")) {
            return parseLift(rest(text));
        }
        return parseBan(text, granted);
    }

    private static Result parseLift(String text) {
        Kind kind = kindOf(firstWord(text));
        String afterKind = rest(text);
        String value = firstWord(afterKind);
        String reason = rest(afterKind);
        if (kind == null) {
            return new Invalid(USAGE);
        }
        String normalised = normalise(kind, value);
        if (normalised == null) {
            return new Invalid(invalidValue(kind));
        }
        if (!validReason(reason)) {
            return new Invalid("Give a reason of 1 to " + MAX_REASON + " characters: /areaban lift " + kind.name().toLowerCase(Locale.ROOT)
                    + " <value> <reason>");
        }
        return new Lift(kind, normalised, reason.strip());
    }

    private static Result parseBan(String text, Predicate<String> granted) {
        Kind kind = kindOf(firstWord(text));
        if (kind == null) {
            return new Invalid(USAGE);
        }
        String afterKind = rest(text);
        String normalised = normalise(kind, firstWord(afterKind));
        if (normalised == null) {
            return new Invalid(invalidValue(kind));
        }
        if (kind == Kind.COUNTRY && !granted.test(COUNTRY_PERMISSION)) {
            return new Invalid("You may not ban a country with this command.");
        }
        String afterValue = rest(afterKind);
        String first = firstWord(afterValue);
        Matcher duration = DURATION.matcher(first);
        Long seconds = null;
        String reason = afterValue;
        if (duration.matches()) {
            long amount = Long.parseLong(duration.group(1));
            if (amount == 0) {
                return new Invalid("The duration must be more than zero: " + first);
            }
            seconds = amount * unitSeconds(duration.group(2));
            reason = rest(afterValue);
        }
        if (!validReason(reason)) {
            return new Invalid("Give a reason of 1 to " + MAX_REASON + " characters: /areaban " + kind.name().toLowerCase(Locale.ROOT)
                    + " <value> [duration] <reason>");
        }
        return new Ban(kind, normalised, seconds, reason.strip());
    }

    private static @Nullable Kind kindOf(String word) {
        if (word.equalsIgnoreCase("asn")) {
            return Kind.ASN;
        }
        if (word.equalsIgnoreCase("country")) {
            return Kind.COUNTRY;
        }
        return null;
    }

    private static @Nullable String normalise(Kind kind, String value) {
        if (kind == Kind.ASN) {
            Matcher asn = ASN.matcher(value);
            if (!asn.matches()) {
                return null;
            }
            long number = Long.parseLong(asn.group(1));
            return number == 0 ? null : Long.toString(number);
        }
        return COUNTRY.matcher(value).matches() ? value.toUpperCase(Locale.ROOT) : null;
    }

    private static String invalidValue(Kind kind) {
        return kind == Kind.ASN ? "An ASN is a number, for example 16276 or AS16276." : "A country is two letters, for example DE.";
    }

    private static long unitSeconds(String unit) {
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "s" -> 1L;
            case "m" -> 60L;
            case "h" -> 3600L;
            default -> 86400L;
        };
    }

    private static boolean validReason(String reason) {
        int length = reason.strip().length();
        return length >= 1 && length <= MAX_REASON;
    }

    private static String firstWord(String text) {
        int end = text.indexOf(' ');
        return end < 0 ? text : text.substring(0, end);
    }

    private static String rest(String text) {
        int end = text.indexOf(' ');
        return end < 0 ? "" : text.substring(end + 1).strip();
    }
}
