package net.onelitefeather.blackhole.backend.clientrule;

import net.onelitefeather.blackhole.backend.support.FakeClientRuleHitStore;
import net.onelitefeather.blackhole.backend.support.FakeClientRuleStore;
import net.onelitefeather.blackhole.backend.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientRuleHitRetentionSweeperTest {

    private static final String OWNER = "ab".repeat(64);

    private final FakeClientRuleStore rules = new FakeClientRuleStore();
    private final FakeClientRuleHitStore hits = new FakeClientRuleHitStore(rules);
    private final TestClock clock = new TestClock();

    @Test
    void hitsOlderThanTheRetentionAreDeletedAndTheRuleKeepsItsTotal() {
        rules.given(ClientRuleSpec.builder("meteor", "Meteor", ClientRuleKind.CHANNEL).patterns("meteor:*").build());
        hits.record("meteor", OWNER, null, false, false, clock.millis());
        clock.advance(Duration.ofDays(20));
        hits.record("meteor", OWNER, null, false, false, clock.millis());
        clock.advance(Duration.ofDays(11));

        new ClientRuleHitRetentionSweeper(hits, clock, 30).sweep();

        assertEquals(1, hits.all().size(), "only the hit from 11 days ago remains");
        assertEquals(2, rules.find("meteor").orElseThrow().hitCount(), "the counter outlives the single hits");
    }

    @Test
    void aHitExactlyAtTheRetentionEdgeIsKept() {
        rules.given(ClientRuleSpec.builder("meteor", "Meteor", ClientRuleKind.CHANNEL).patterns("meteor:*").build());
        hits.record("meteor", OWNER, null, false, false, clock.millis());
        clock.advance(Duration.ofDays(30));

        new ClientRuleHitRetentionSweeper(hits, clock, 30).sweep();

        assertEquals(1, hits.all().size());
    }

    @Test
    void theRetentionIsConfigurable() {
        rules.given(ClientRuleSpec.builder("meteor", "Meteor", ClientRuleKind.CHANNEL).patterns("meteor:*").build());
        hits.record("meteor", OWNER, null, false, false, clock.millis());
        clock.advance(Duration.ofDays(8));

        new ClientRuleHitRetentionSweeper(hits, clock, 7).sweep();

        assertEquals(0, hits.all().size());
    }
}
