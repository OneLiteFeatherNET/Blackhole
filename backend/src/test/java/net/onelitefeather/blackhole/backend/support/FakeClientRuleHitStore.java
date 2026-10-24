package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.clientrule.ClientRuleHitStore;
import net.onelitefeather.blackhole.backend.clientrule.ClientRuleStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** An in-memory {@link ClientRuleHitStore} over a {@link FakeClientRuleStore}. */
public final class FakeClientRuleHitStore implements ClientRuleHitStore {

    private final FakeClientRuleStore rules;
    private final List<Hit> hits = new ArrayList<>();

    public FakeClientRuleHitStore(FakeClientRuleStore rules) {
        this.rules = rules;
    }

    public List<Hit> all() {
        return List.copyOf(this.hits);
    }

    @Override
    public Optional<RecordedHit> record(String ruleKey, String owner, String server, boolean enforced, boolean bypassed, long at) {
        ClientRuleStore.Stored rule = this.rules.find(ruleKey).filter(found -> found.spec().enabled()).orElse(null);
        if (rule == null) {
            return Optional.empty();
        }
        this.rules.countHit(ruleKey);
        this.hits.add(new Hit(ruleKey, owner, at, rule.spec().action(), enforced, bypassed, server));
        return Optional.of(new RecordedHit(ruleKey, rule.spec().kind(), rule.spec().action(), rule.spec().templateKey()));
    }

    @Override
    public List<Hit> byRule(String ruleKey, int limit) {
        return this.hits.stream().filter(hit -> hit.ruleKey().equals(ruleKey)).sorted(Comparator.comparingLong(Hit::at).reversed()).limit(limit).toList();
    }

    @Override
    public List<Hit> byOwner(String owner, int limit) {
        return this.hits.stream().filter(hit -> hit.owner().equals(owner)).sorted(Comparator.comparingLong(Hit::at).reversed()).limit(limit).toList();
    }

    @Override
    public int deleteBefore(long cutoff) {
        int before = this.hits.size();
        this.hits.removeIf(hit -> hit.at() < cutoff);
        return before - this.hits.size();
    }

    @Override
    public int deleteByOwner(String owner) {
        int before = this.hits.size();
        this.hits.removeIf(hit -> hit.owner().equals(owner));
        return before - this.hits.size();
    }
}
