package net.onelitefeather.blackhole.backend.punishment;

import java.util.List;

/**
 * Picks the escalation step for a template application: step {@code min(n, stepCount - 1)}
 * (zero-based), where {@code n} is the number of earlier counted punishments in the template's
 * escalation group. Beyond the last step, the last step repeats.
 */
public final class StepSelector {

    /**
     * @param index     the zero-based index of the chosen step
     * @param stepCount how many steps the template has
     * @param step      the chosen step
     */
    public record Selection(int index, int stepCount, TemplateStep step) {
    }

    /**
     * @param steps           the template's ordered steps, at least one
     * @param countedEarlier  how many earlier punishments of the owner count towards this group
     */
    public static Selection select(List<TemplateStep> steps, long countedEarlier) {
        int index = (int) Math.min(Math.max(countedEarlier, 0), steps.size() - 1L);
        return new Selection(index, steps.size(), steps.get(index));
    }

    private StepSelector() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
