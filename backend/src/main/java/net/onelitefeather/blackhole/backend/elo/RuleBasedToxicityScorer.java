package net.onelitefeather.blackhole.backend.elo;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Default {@link ToxicityScorer}: counts substring matches against a configurable keyword list.
 * The built-in default list is deliberately mild/placeholder (not a real moderation wordlist) -
 * operators are expected to either override {@code blackhole.elo.chat.toxic-patterns} with a
 * real list or replace this bean entirely with something more sophisticated.
 */
@Singleton
public class RuleBasedToxicityScorer implements ToxicityScorer {

    private final List<String> patterns;
    private final double severityPerMatch;

    public RuleBasedToxicityScorer(
            @Value("${blackhole.elo.chat.toxic-patterns:stupid,idiot,shut up,hate you}") String patternsCsv,
            @Value("${blackhole.elo.chat.severity-per-match:0.4}") double severityPerMatch
    ) {
        this.patterns = Arrays.stream(patternsCsv.split(","))
                .map(String::trim)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .filter(s -> !s.isBlank())
                .toList();
        this.severityPerMatch = severityPerMatch;
    }

    @Override
    public double score(String message) {
        if (message == null || message.isBlank()) {
            return 0.0;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        long matches = this.patterns.stream().filter(lower::contains).count();
        return Math.min(1.0, matches * this.severityPerMatch);
    }
}
