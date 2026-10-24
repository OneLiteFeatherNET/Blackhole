package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.clientrule.ClientRuleSpec;
import net.onelitefeather.blackhole.backend.clientrule.ClientRuleStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * An in-memory {@link ClientRuleStore}. Templates "exist" only when a test registers them, so the
 * foreign key to the template table can be simulated.
 */
public final class FakeClientRuleStore implements ClientRuleStore {

    private final TreeMap<String, Stored> rules = new TreeMap<>();
    private final Set<String> knownTemplates = new HashSet<>();

    /** Makes a template key resolvable, as the foreign key needs. */
    public void givenTemplate(String templateKey) {
        this.knownTemplates.add(templateKey);
    }

    public boolean templateKnown(String templateKey) {
        return this.knownTemplates.contains(templateKey);
    }

    /** Stores a rule directly, bypassing the service. */
    public void given(ClientRuleSpec spec) {
        this.rules.put(spec.key(), new Stored(spec, 0, 0, 0, null));
    }

    /** Counts a hit on the rule, as the real store does in the statement that stores it. */
    public void countHit(String key) {
        Stored before = this.rules.get(key);
        this.rules.put(key, new Stored(before.spec(), before.hitCount() + 1, before.createdAt(), before.updatedAt(), before.recordedByOid()));
    }

    public List<Stored> all() {
        return new ArrayList<>(this.rules.values());
    }

    @Override
    public Write insert(ClientRuleSpec spec, UUID recordedByOid, long now) {
        if (this.rules.containsKey(spec.key())) {
            return Write.TAKEN;
        }
        if (spec.templateKey() != null && !this.knownTemplates.contains(spec.templateKey())) {
            return Write.MISSING_TEMPLATE;
        }
        this.rules.put(spec.key(), new Stored(spec, 0, now, now, recordedByOid));
        return Write.DONE;
    }

    @Override
    public Write update(ClientRuleSpec spec, UUID recordedByOid, long now) {
        Stored before = this.rules.get(spec.key());
        if (before == null) {
            return Write.ABSENT;
        }
        if (spec.templateKey() != null && !this.knownTemplates.contains(spec.templateKey())) {
            return Write.MISSING_TEMPLATE;
        }
        this.rules.put(spec.key(), new Stored(spec, before.hitCount(), before.createdAt(), now, recordedByOid));
        return Write.DONE;
    }

    @Override
    public Optional<Stored> find(String key) {
        return Optional.ofNullable(this.rules.get(key));
    }

    @Override
    public List<Stored> list() {
        return all();
    }

    @Override
    public boolean delete(String key) {
        return this.rules.remove(key) != null;
    }

    @Override
    public boolean templateInUse(String templateKey) {
        return this.rules.values().stream().anyMatch(rule -> templateKey.equals(rule.spec().templateKey()));
    }
}
