package net.onelitefeather.blackhole.backend.clientrule;

import io.micronaut.core.annotation.Nullable;

/**
 * Applies a rule's punishment template to a player through the same path as the ELO automatic
 * punishments: no human actor, a system source naming the rule, a Ban ID, the template's step and
 * ELO cost, open to appeal. Implemented by the punishment application service; the rule code depends
 * on this narrow interface only.
 */
public interface ClientRuleTemplateApplier {

    /** What happened to the template. */
    enum Outcome {

        /** A punishment was created. */
        APPLIED,

        /** The player still has an active punishment from the same rule; nothing was added and no step escalated. */
        ALREADY_ACTIVE,

        /** Nothing was applied: unknown template, disabled section, or a permanent punishment already holds the section. */
        NOT_APPLIED
    }

    /**
     * @param punishmentIdentifier the created punishment, only for {@link Outcome#APPLIED}
     */
    record Result(Outcome outcome, @Nullable String punishmentIdentifier) {
    }

    /**
     * @param owner       the hashed owner
     * @param templateKey the rule's template
     * @param ruleKey     the rule, recorded on the punishment as its source
     */
    Result applyForRule(String owner, String templateKey, String ruleKey);
}
