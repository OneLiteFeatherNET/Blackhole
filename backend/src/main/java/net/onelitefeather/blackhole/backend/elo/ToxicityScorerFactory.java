package net.onelitefeather.blackhole.backend.elo;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

/** Provides the default {@link ToxicityScorer} from the configured word lists. */
@Factory
public class ToxicityScorerFactory {

    @Singleton
    ToxicityScorer toxicityScorer(ToxicityWordLists lists) {
        return TermListToxicityScorer.fromWordLists(lists.getTerms(), lists.getAllowList(), lists.getWeights());
    }
}
