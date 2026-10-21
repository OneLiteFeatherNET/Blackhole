package net.onelitefeather.blackhole.backend.elo;

import io.micronaut.context.annotation.Secondary;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.section.dto.SectionDTO;

/**
 * The default escalation rule.
 *
 * <ul>
 *     <li><b>Permanent tier</b>: after any decrease, a score below the permanent threshold punishes
 *     permanently unless the owner already has an active permanent punishment in the section. This is
 *     a state rule, not a crossing rule, so a pardoned player who is still below the threshold is
 *     escalated again on the next deduction.</li>
 *     <li><b>Soft tier</b>: only a genuine downward crossing (from at or above the soft threshold to
 *     below it) punishes temporarily, otherwise every offence below it would add another automatic
 *     punishment. A change that also falls below the permanent threshold is a permanent escalation
 *     only.</li>
 * </ul>
 *
 * <p>While the owner has an active permanent punishment nothing is decided, so a soft punishment can
 * never replace the permanent one in the section's slot.</p>
 */
@Secondary
@Singleton
public class ThresholdEscalationPolicy implements EscalationPolicy {

    @Override
    public Decision decide(Context context) {
        if (!context.decreased() || context.hasActivePermanent()) {
            return Decision.NONE;
        }
        SectionDTO.Response section = context.section();
        if (context.scoreAfter() < section.permanentThreshold()) {
            return Decision.PERMANENT;
        }
        Integer soft = section.softThreshold();
        if (soft != null && context.scoreBefore() >= soft && context.scoreAfter() < soft) {
            return Decision.SOFT;
        }
        return Decision.NONE;
    }
}
