package net.onelitefeather.blackhole.backend.clientrule;

import io.micronaut.core.annotation.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * Persistence of rule hits. A hit carries no client trait: only the rule key, the hashed owner, the
 * time, the action, whether it was enforced or bypassed, and the server name.
 */
public interface ClientRuleHitStore {

    /** What the store knows about the rule a hit was recorded for. */
    record RecordedHit(String ruleKey, ClientRuleKind kind, ClientRuleAction action, @Nullable String templateKey) {
    }

    /** One stored hit. */
    record Hit(String ruleKey, String owner, long at, ClientRuleAction action, boolean enforced, boolean bypassed, @Nullable String server) {
    }

    /**
     * Stores a hit and increments the rule's counter in the same statement.
     *
     * @return the rule, or empty when no enabled rule has that key (nothing is stored then)
     */
    Optional<RecordedHit> record(String ruleKey, String owner, @Nullable String server, boolean enforced, boolean bypassed, long at);

    /** The newest hits of a rule, newest first. */
    List<Hit> byRule(String ruleKey, int limit);

    /** The newest hits of a player, newest first. */
    List<Hit> byOwner(String owner, int limit);

    /** Deletes hits older than {@code cutoff} (epoch milliseconds); the rule counters are untouched. */
    int deleteBefore(long cutoff);

    /** Deletes every hit of a player (erasure). */
    int deleteByOwner(String owner);
}
