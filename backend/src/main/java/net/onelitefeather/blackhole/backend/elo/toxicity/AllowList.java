package net.onelitefeather.blackhole.backend.elo.toxicity;

import java.util.Collection;
import java.util.List;

/**
 * Game phrases that must not trigger a term match, for example {@code kill the dragon}. It applies to the
 * tagger only; the model score is never changed by it.
 */
public final class AllowList {

    private static final AllowList EMPTY = new AllowList(List.of());

    private final List<String> phrases;

    private AllowList(List<String> phrases) {
        this.phrases = phrases;
    }

    public static AllowList empty() {
        return EMPTY;
    }

    public static AllowList of(Collection<String> phrases) {
        return new AllowList(phrases.stream().filter(p -> !p.isBlank()).toList());
    }

    List<String> phrases() {
        return phrases;
    }
}
