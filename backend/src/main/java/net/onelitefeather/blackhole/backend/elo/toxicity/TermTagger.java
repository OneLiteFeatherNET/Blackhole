package net.onelitefeather.blackhole.backend.elo.toxicity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tags a message with the categories whose German or English terms it contains.
 *
 * <p>A term matches the plain form on word boundaries. It matches the squashed form (spacing and punctuation
 * removed) only when it has at least four letters, which keeps short words from matching by accident.
 * Allow-listed phrases are removed from the text before matching. The term lists are copied in and never
 * change.
 */
public final class TermTagger {

    private static final int SQUASH_MIN_LENGTH = 4;

    private final ChatNormaliser normaliser;
    private final Map<ToxicityCategory, List<Term>> terms;

    public TermTagger(ChatNormaliser normaliser, Map<ToxicityCategory, ? extends Collection<String>> terms) {
        this.normaliser = normaliser;
        Map<ToxicityCategory, List<Term>> compiled = new EnumMap<>(ToxicityCategory.class);
        terms.forEach((category, words) -> compiled.put(category, words.stream()
                .filter(word -> !word.isBlank())
                .map(this::compile)
                .toList()));
        this.terms = Map.copyOf(compiled);
    }

    public Set<ToxicityCategory> tag(String message, AllowList allowList) {
        TaggerView view = normaliser.taggerView(message);
        String folded = view.folded();
        String expanded = view.expanded();
        for (String phrase : allowList.phrases()) {
            TaggerView phraseView = normaliser.taggerView(phrase);
            folded = folded.replace(phraseView.folded(), " ");
            expanded = expanded.replace(phraseView.expanded(), " ");
        }
        String squashedFolded = TaggerView.squash(folded);
        String squashedExpanded = TaggerView.squash(expanded);

        Set<ToxicityCategory> matched = EnumSet.noneOf(ToxicityCategory.class);
        for (Map.Entry<ToxicityCategory, List<Term>> entry : terms.entrySet()) {
            for (Term term : entry.getValue()) {
                if (term.plainMatches(folded) || term.plainMatches(expanded)
                        || term.squashedMatches(squashedFolded) || term.squashedMatches(squashedExpanded)) {
                    matched.add(entry.getKey());
                }
            }
        }
        return matched;
    }

    private Term compile(String word) {
        TaggerView form = normaliser.taggerView(word);
        return new Term(
                plainPattern(form.folded()),
                plainPattern(form.expanded()),
                TaggerView.squash(form.folded()),
                TaggerView.squash(form.expanded()));
    }

    private static Pattern plainPattern(String word) {
        return Pattern.compile("(?<!\\p{L})" + Pattern.quote(word) + "(?!\\p{L})");
    }

    private record Term(Pattern plainFolded, Pattern plainExpanded, String squashedFolded, String squashedExpanded) {

        boolean plainMatches(String text) {
            return plainFolded.matcher(text).find() || plainExpanded.matcher(text).find();
        }

        boolean squashedMatches(String squashed) {
            return squashedFolded.length() >= SQUASH_MIN_LENGTH && squashed.contains(squashedFolded)
                    || squashedExpanded.length() >= SQUASH_MIN_LENGTH && squashed.contains(squashedExpanded);
        }
    }
}
