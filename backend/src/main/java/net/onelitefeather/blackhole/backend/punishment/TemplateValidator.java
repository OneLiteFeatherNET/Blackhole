package net.onelitefeather.blackhole.backend.punishment;

import net.onelitefeather.blackhole.backend.punishment.dto.PunishTemplateDTO;
import net.onelitefeather.blackhole.backend.section.SectionValidator;
import net.onelitefeather.blackhole.backend.utils.Durations;

import java.util.ArrayList;
import java.util.List;

/**
 * Business rules for a template definition, independent of how it is stored. Whether the section
 * exists and whether the key is taken need the database and are checked by the service.
 */
public final class TemplateValidator {

    private static final int MAX_KEY_LENGTH = 64;
    private static final int MAX_REASON_LENGTH = 255;
    private static final int MAX_STEPS = 20;

    /**
     * @return one message per violated rule, empty if {@code spec} is valid
     */
    public static List<String> validate(TemplateSpec spec) {
        List<String> errors = new ArrayList<>();
        if (!isSlug(spec.key())) {
            errors.add("key must be kebab-case (lower-case letters, digits and single hyphens, at most " + MAX_KEY_LENGTH + " characters)");
        }
        if (spec.reason() == null || spec.reason().isBlank() || spec.reason().length() > MAX_REASON_LENGTH) {
            errors.add("reason must be 1 to " + MAX_REASON_LENGTH + " characters");
        }
        if (spec.section() == null || spec.section().isBlank()) {
            errors.add("section is required");
        }
        if (spec.escalationGroup() != null && !spec.escalationGroup().isBlank() && !isSlug(spec.escalationGroup())) {
            errors.add("escalationGroup must be kebab-case when set");
        }
        if (spec.escalationWindow() != null && Durations.parsePositive(spec.escalationWindow()).isEmpty()) {
            errors.add("escalationWindow must be a positive ISO-8601 duration when set");
        }
        if (spec.warnWindow() != null && !spec.warnWindow().isBlank() && Durations.parsePositive(spec.warnWindow()).isEmpty()) {
            errors.add("warnWindow must be a positive ISO-8601 duration when set");
        }
        validateSteps(spec.steps(), errors);
        return errors;
    }

    private static void validateSteps(List<PunishTemplateDTO.Step> steps, List<String> errors) {
        if (steps == null || steps.isEmpty()) {
            errors.add("a template needs at least one step; permanent templates do not exist");
            return;
        }
        if (steps.size() > MAX_STEPS) {
            errors.add("a template may have at most " + MAX_STEPS + " steps");
        }
        for (int i = 0; i < steps.size(); i++) {
            PunishTemplateDTO.Step step = steps.get(i);
            if (step == null) {
                errors.add("step " + (i + 1) + " is missing");
                continue;
            }
            if (step.kind() == StepKind.KICK || step.kind() == StepKind.WARN) {
                if (step.duration() != null && !step.duration().isBlank()) {
                    errors.add("step " + (i + 1) + " is a " + step.kind() + " step and must not have a duration");
                }
            } else if (step.duration() == null || Durations.parsePositive(step.duration()).isEmpty()) {
                errors.add("step " + (i + 1) + " needs a duration that is a positive ISO-8601 duration");
            }
            if (step.eloCost() < 0) {
                errors.add("step " + (i + 1) + " eloCost must not be negative");
            }
        }
    }

    private static boolean isSlug(String value) {
        return value != null && value.length() <= MAX_KEY_LENGTH && SectionValidator.KEBAB_CASE.matcher(value).matches();
    }

    private TemplateValidator() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
