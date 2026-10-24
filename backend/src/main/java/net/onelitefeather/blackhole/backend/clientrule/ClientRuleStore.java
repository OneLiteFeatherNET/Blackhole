package net.onelitefeather.blackhole.backend.clientrule;

import io.micronaut.core.annotation.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence of client rules. A write stores the rule and its pattern, server and section rows
 * together or not at all (one statement, no {@code @Transactional}).
 */
public interface ClientRuleStore extends ClientRuleUsage {

    /**
     * A stored rule.
     *
     * @param hitCount        the running total of hits; kept after the single hits expire
     * @param recordedByOid   the verified staff member who last wrote it; never part of a response
     */
    record Stored(ClientRuleSpec spec, long hitCount, long createdAt, long updatedAt, @Nullable UUID recordedByOid) {
    }

    /** The outcome of a write. */
    enum Write {

        /** The rule was written. */
        DONE,

        /** A create found the key already in use. */
        TAKEN,

        /** An update found no rule with that key. */
        ABSENT,

        /** The template disappeared between the check and the write. */
        MISSING_TEMPLATE
    }

    /** Inserts a new rule; never replaces one. */
    Write insert(ClientRuleSpec spec, @Nullable UUID recordedByOid, long now);

    /** Replaces every editable field of an existing rule and keeps its hit count and creation time. */
    Write update(ClientRuleSpec spec, @Nullable UUID recordedByOid, long now);

    Optional<Stored> find(String key);

    /** Every rule, sorted by key. */
    List<Stored> list();

    /** @return whether a rule was deleted */
    boolean delete(String key);
}
