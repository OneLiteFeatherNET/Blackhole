package net.onelitefeather.blackhole.backend.firewall;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.ipban.IpBanFixture;
import net.onelitefeather.blackhole.backend.ipban.IpBanNetwork;
import net.onelitefeather.blackhole.backend.ipban.IpBanProtection;
import net.onelitefeather.blackhole.backend.ipban.IpBanRecord;
import net.onelitefeather.blackhole.backend.ipban.IpBanSource;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AliasReconcilerTest {

    private static final long NOW = IpBanFixture.NOW;

    /** An in-memory alias: writes change the entries, and reads return them. */
    static class FakeGateway implements AliasGateway {
        final Set<String> entries = new HashSet<>();
        final List<String> writes = new ArrayList<>();
        int reconfigures;
        boolean unreachable;

        @Override
        public Set<String> entries(String alias) {
            if (this.unreachable) {
                throw new AliasException("list", "the firewall could not be reached");
            }
            return new HashSet<>(this.entries);
        }

        @Override
        public void add(String alias, String range) {
            this.writes.add("add " + range);
            this.entries.add(range);
        }

        @Override
        public void delete(String alias, String range) {
            this.writes.add("delete " + range);
            this.entries.remove(range);
        }

        @Override
        public void reconfigure() {
            this.reconfigures++;
        }
    }

    private static AliasReconciler reconciler(IpBanFixture fixture, FakeGateway gateway, SimpleMeterRegistry meters, boolean enabled) {
        return new AliasReconciler(fixture.store, new IpBanProtection(""), gateway, fixture.clock, meters, enabled,
                "blackhole_ipban", Duration.ofMinutes(5));
    }

    private static void ban(IpBanFixture fixture, String range) {
        IpBanNetwork network = IpBanNetwork.parse(range).orElseThrow();
        fixture.store.insert(new IpBanRecord(UUID.randomUUID(), network.network(), "Bot wave", null, IpBanSource.PANEL,
                null, null, IpBanFixture.ADMIN_OID, NOW - 10, null, null, null, null));
    }

    @Test
    void reconciliationAddsTheMissingEntriesAndReconfiguresOnce() {
        IpBanFixture fixture = new IpBanFixture();
        ban(fixture, "203.0.113.7");
        ban(fixture, "198.51.100.0/24");
        FakeGateway gateway = new FakeGateway();

        AliasReconciler.Outcome outcome = reconciler(fixture, gateway, new SimpleMeterRegistry(), true).reconcile();

        assertEquals(AliasReconciler.Outcome.IN_SYNC, outcome, "the run succeeds");
        assertEquals(Set.of("203.0.113.7/32", "198.51.100.0/24"), gateway.entries, "the alias holds the active bans");
        assertEquals(1, gateway.reconfigures, "one reconfigure after the writes");
    }

    @Test
    void anUnchangedAliasMakesNoWriteAndNoReconfigure() {
        IpBanFixture fixture = new IpBanFixture();
        ban(fixture, "203.0.113.7");
        FakeGateway gateway = new FakeGateway();
        gateway.entries.add("203.0.113.7/32");

        reconciler(fixture, gateway, new SimpleMeterRegistry(), true).reconcile();

        assertEquals(List.of(), gateway.writes, "an identical alias is not written");
        assertEquals(0, gateway.reconfigures, "and not reconfigured");
    }

    @Test
    void anEntryAddedByHandIsRemovedOnTheNextRun() {
        IpBanFixture fixture = new IpBanFixture();
        FakeGateway gateway = new FakeGateway();
        gateway.entries.add("192.0.2.1/32");

        reconciler(fixture, gateway, new SimpleMeterRegistry(), true).reconcile();

        assertTrue(gateway.entries.isEmpty(), "the database decides, so the stray entry goes");
    }

    @Test
    void aReadBackThatDiffersFailsTheRun() {
        IpBanFixture fixture = new IpBanFixture();
        ban(fixture, "203.0.113.7");
        FakeGateway gateway = new FakeGateway() {
            @Override
            public void add(String alias, String range) {
                // accepted by the API but never applied
            }
        };

        AliasReconciler.Outcome outcome = reconciler(fixture, gateway, new SimpleMeterRegistry(), true).reconcile();

        assertEquals(AliasReconciler.Outcome.FAILED, outcome, "a read-back mismatch is a failed run");
    }

    @Test
    void aProtectedRowIsNeverWrittenAndIsCounted() {
        IpBanFixture fixture = new IpBanFixture();
        ban(fixture, "127.0.0.1");
        FakeGateway gateway = new FakeGateway();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        reconciler(fixture, gateway, meters, true).reconcile();

        assertTrue(gateway.entries.isEmpty(), "a bad row never reaches the alias");
        assertEquals(1.0, meters.counter("blackhole.ipban.alias.refused").count(), "the refusal is counted");
    }

    @Test
    void anUnreachableFirewallIsCountedAndBacksOff() {
        IpBanFixture fixture = new IpBanFixture();
        FakeGateway gateway = new FakeGateway();
        gateway.unreachable = true;
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AliasReconciler reconciler = reconciler(fixture, gateway, meters, true);

        reconciler.reconcile();
        AliasReconciler.Outcome second = reconciler.reconcile();

        assertEquals(AliasReconciler.Outcome.SKIPPED, second, "the next run waits instead of bursting requests");
        assertEquals(1.0, meters.counter("blackhole.ipban.alias.errors", "operation", "list").count(),
                "the failure is counted by operation");
    }

    @Test
    void theLayerOffMakesNoRequest() {
        IpBanFixture fixture = new IpBanFixture();
        ban(fixture, "203.0.113.7");
        FakeGateway gateway = new FakeGateway();

        AliasReconciler.Outcome outcome = reconciler(fixture, gateway, new SimpleMeterRegistry(), false).reconcile();

        assertEquals(AliasReconciler.Outcome.DISABLED, outcome, "the firewall layer is off by default");
        assertEquals(List.of(), gateway.writes, "nothing is written");
    }

    /** A clock the test moves forward explicitly; no system time is read. */
    static final class MovableClock extends Clock {
        long millis = NOW;

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(this.millis);
        }
    }

    @Test
    void aChangeIsWrittenOnTheNextTickAfterTheDebounce() {
        IpBanFixture fixture = new IpBanFixture();
        MovableClock clock = new MovableClock();
        FakeGateway gateway = new FakeGateway();
        AliasReconciler reconciler = new AliasReconciler(fixture.store, new IpBanProtection(""), gateway, clock,
                new SimpleMeterRegistry(), true, "blackhole_ipban", Duration.ofMinutes(5));
        reconciler.tick();
        ban(fixture, "203.0.113.9");
        clock.millis += Duration.ofSeconds(6).toMillis();

        reconciler.changed();
        reconciler.tick();

        assertEquals(Set.of("203.0.113.9/32"), gateway.entries, "a change is written on the next tick, not after the interval");
    }
}
