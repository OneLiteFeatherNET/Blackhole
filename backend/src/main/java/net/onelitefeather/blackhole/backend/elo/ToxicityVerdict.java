package net.onelitefeather.blackhole.backend.elo;

import net.onelitefeather.blackhole.backend.elo.toxicity.ToxicityCategory;

import java.util.Optional;

/**
 * The result of scoring one chat message: the score in {@code [0.0, 1.0]} and the category that produced it,
 * empty when nothing matched.
 */
public record ToxicityVerdict(double score, Optional<ToxicityCategory> category) {

    public static final ToxicityVerdict CLEAN = new ToxicityVerdict(0.0, Optional.empty());
}
