package net.onelitefeather.blackhole.backend.elo;

/**
 * Scores how toxic a chat message is. Deliberately a single, swappable bean - the default
 * {@link RuleBasedToxicityScorer} is an explicitly basic placeholder; replacing it with an ML
 * classifier later (per the roadmap) is a matter of providing a different {@code ToxicityScorer}
 * bean (e.g. via {@code @Replaces}), not touching any of its callers.
 */
public interface ToxicityScorer {

    /**
     * @param message the raw chat message text
     * @return a score in {@code [0.0, 1.0]} - 0 is clean, 1 is maximally toxic
     */
    double score(String message);
}
