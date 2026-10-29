package net.onelitefeather.blackhole.backend.elo;

/**
 * Scores how toxic a chat message is. Deliberately a single, swappable bean: the default
 * {@link TermListToxicityScorer} matches configured word lists, and a different implementation can be
 * provided as a bean (e.g. via {@code @Replaces}) without touching any of its callers.
 */
public interface ToxicityScorer {

    /**
     * @param message the raw chat message text
     * @return the verdict, whose score is in {@code [0.0, 1.0]}, 0 being clean and 1 maximally toxic
     */
    ToxicityVerdict score(String message);
}
