package net.onelitefeather.blackhole.backend.section;

import net.onelitefeather.blackhole.backend.utils.Durations;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Business rules for a section definition, independent of how it is stored.
 */
public final class SectionValidator {

    /** Kebab-case: lower-case words of letters and digits joined by single hyphens. */
    public static final Pattern KEBAB_CASE = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private static final int MAX_KEY_LENGTH = 64;
    private static final int MAX_DISPLAY_NAME_LENGTH = 128;
    private static final int MAX_REASON_LENGTH = 255;

    /**
     * @return one message per violated rule, empty if {@code spec} is valid
     */
    public static List<String> validate(SectionSpec spec) {
        List<String> errors = new ArrayList<>();
        if (spec.key() == null || !KEBAB_CASE.matcher(spec.key()).matches() || spec.key().length() > MAX_KEY_LENGTH) {
            errors.add("key must be kebab-case (lower-case letters, digits and single hyphens, at most " + MAX_KEY_LENGTH + " characters)");
        }
        if (spec.displayName() == null || spec.displayName().isBlank() || spec.displayName().length() > MAX_DISPLAY_NAME_LENGTH) {
            errors.add("displayName must be 1 to " + MAX_DISPLAY_NAME_LENGTH + " characters");
        }
        if (spec.enforcement() == null) {
            errors.add("enforcement is required");
        } else {
            errors.addAll(ServerPatterns.validate(spec.enforcement(), spec.servers()));
        }
        if (spec.permanentThreshold() >= spec.baseScore()) {
            errors.add("permanentThreshold must be lower than baseScore");
        }
        if (spec.pardonScore() <= spec.permanentThreshold()) {
            errors.add("pardonScore must be above permanentThreshold");
        }
        validateSoftTier(spec, errors);
        if (spec.permanentReason() == null || spec.permanentReason().isBlank() || spec.permanentReason().length() > MAX_REASON_LENGTH) {
            errors.add("permanentReason must be 1 to " + MAX_REASON_LENGTH + " characters");
        }
        if (spec.decayPerDay() < 0) {
            errors.add("decayPerDay must not be negative");
        }
        return errors;
    }

    private static void validateSoftTier(SectionSpec spec, List<String> errors) {
        if (spec.softThreshold() == null) {
            if (spec.softDuration() != null) {
                errors.add("softDuration requires a softThreshold");
            }
            return;
        }
        if (spec.softThreshold() <= spec.permanentThreshold() || spec.softThreshold() > spec.baseScore()) {
            errors.add("softThreshold must be above permanentThreshold and not above baseScore");
        }
        if (Durations.parsePositive(spec.softDuration()).isEmpty()) {
            errors.add("softDuration must be a positive ISO-8601 duration when a softThreshold is set");
        }
    }

    private SectionValidator() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
