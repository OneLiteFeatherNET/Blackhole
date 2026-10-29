package net.onelitefeather.blackhole.backend.elo;

import net.onelitefeather.blackhole.backend.elo.toxicity.AllowList;
import net.onelitefeather.blackhole.backend.elo.toxicity.ChatNormaliser;
import net.onelitefeather.blackhole.backend.elo.toxicity.TermTagger;
import net.onelitefeather.blackhole.backend.elo.toxicity.ToxicityCategory;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Scores a message from configured word lists, in-process and without any network call. The score is the
 * highest weight among the categories whose terms match, so a threat hits harder than a mild insult.
 */
public final class TermListToxicityScorer implements ToxicityScorer {

    /** Defaults for {@code blackhole.elo.chat.toxicity.weights.<category>}, used where the config is silent. */
    static final Map<ToxicityCategory, Double> DEFAULT_WEIGHTS = Map.of(
            ToxicityCategory.THREAT, 1.0,
            ToxicityCategory.HATE, 0.9,
            ToxicityCategory.SEXUAL, 0.8,
            ToxicityCategory.INSULT, 0.6,
            ToxicityCategory.SPAM, 0.5);

    private final TermTagger tagger;
    private final AllowList allowList;
    private final Map<ToxicityCategory, Double> weights;

    public TermListToxicityScorer(TermTagger tagger, AllowList allowList, Map<ToxicityCategory, Double> weights) {
        this.tagger = tagger;
        this.allowList = allowList;
        Map<ToxicityCategory, Double> copy = new EnumMap<>(ToxicityCategory.class);
        weights.forEach((category, weight) -> {
            if (weight == null || weight < 0.0 || weight > 1.0) {
                throw new IllegalArgumentException("toxicity weight for " + category + " must be between 0 and 1, was " + weight);
            }
            copy.put(category, weight);
        });
        this.weights = copy;
    }

    /**
     * Builds the scorer from the raw configuration values. Keys are category names, case-insensitive. Values
     * are comma-separated. An unknown category fails here, at startup, rather than being ignored.
     */
    public static TermListToxicityScorer fromWordLists(Map<String, String> termsByCategory, String allowListCsv,
                                                       Map<String, Double> weightsByCategory) {
        Map<ToxicityCategory, List<String>> terms = new EnumMap<>(ToxicityCategory.class);
        termsByCategory.forEach((key, csv) -> terms.put(category(key), split(csv)));

        Map<ToxicityCategory, Double> weights = new EnumMap<>(DEFAULT_WEIGHTS);
        weightsByCategory.forEach((key, weight) -> weights.put(category(key), weight));

        return new TermListToxicityScorer(
                new TermTagger(new ChatNormaliser(true), terms),
                AllowList.of(split(allowListCsv)),
                weights);
    }

    @Override
    public ToxicityVerdict score(String message) {
        if (message == null || message.isBlank()) {
            return ToxicityVerdict.CLEAN;
        }
        ToxicityCategory best = null;
        double bestWeight = 0.0;
        for (ToxicityCategory category : this.tagger.tag(message, this.allowList)) {
            double weight = this.weights.getOrDefault(category, 0.0);
            if (weight > bestWeight) {
                best = category;
                bestWeight = weight;
            }
        }
        return best == null ? ToxicityVerdict.CLEAN : new ToxicityVerdict(bestWeight, Optional.of(best));
    }

    private static ToxicityCategory category(String key) {
        try {
            return ToxicityCategory.valueOf(key.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("unknown chat toxicity category: " + key, unknown);
        }
    }

    private static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(word -> !word.isEmpty()).toList();
    }
}
