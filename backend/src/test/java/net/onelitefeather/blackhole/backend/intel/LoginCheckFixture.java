package net.onelitefeather.blackhole.backend.intel;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.areaban.AreaBan;
import net.onelitefeather.blackhole.backend.areaban.AreaBanCache;
import net.onelitefeather.blackhole.backend.areaban.AreaBanService;
import net.onelitefeather.blackhole.backend.areaban.InMemoryAreaBanStore;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.guard.BurstSettings;
import net.onelitefeather.blackhole.backend.guard.GuardLevel;
import net.onelitefeather.blackhole.backend.guard.GuardRefusalService;
import net.onelitefeather.blackhole.backend.guard.InMemoryGuardRefusalStore;
import net.onelitefeather.blackhole.backend.guard.GuardState;
import net.onelitefeather.blackhole.backend.guard.GuardStateCache;
import net.onelitefeather.blackhole.backend.guard.InMemoryGuardStateStore;
import net.onelitefeather.blackhole.backend.guard.InMemoryOwnerDirectoryStore;
import net.onelitefeather.blackhole.backend.guard.OwnerDirectoryCache;
import net.onelitefeather.blackhole.backend.intel.dto.LoginCheckDTO;
import net.onelitefeather.blackhole.backend.support.InMemoryIntelRecordStore;
import net.onelitefeather.blackhole.backend.support.RecordingEventTransport;
import net.onelitefeather.blackhole.backend.support.TestClock;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** One login-check wiring per test: a fresh store, clock, transport and classifier. */
final class LoginCheckFixture {

    static final String OWNER = "b".repeat(128);
    static final String VPN_IP = "192.0.2.10";
    static final String HOSTING_IP = "198.51.100.7";
    static final String CLEAN_IP = "203.0.113.5";

    final TestClock clock = new TestClock();
    final InMemoryIntelRecordStore store = new InMemoryIntelRecordStore();
    final RecordingEventTransport transport = new RecordingEventTransport();
    final IntelClassifierHolder holder = new IntelClassifierHolder();
    final IntelPolicyCache policies = new IntelPolicyCache(this.store);
    final IntelHitService hits = new IntelHitService(this.store);
    final IntelFlagNotices notices = new IntelFlagNotices();
    final InMemoryAreaBanStore areaStore = new InMemoryAreaBanStore();
    final AreaBanCache areaBans = new AreaBanCache(this.areaStore);
    final InMemoryGuardStateStore guardStore = new InMemoryGuardStateStore(GuardState.initial(0L));
    final GuardStateCache guard = new GuardStateCache(this.guardStore);
    final InMemoryOwnerDirectoryStore ownerStore = new InMemoryOwnerDirectoryStore();
    final OwnerDirectoryCache owners = new OwnerDirectoryCache(this.ownerStore, Duration.ofMinutes(60));
    final InMemoryGuardRefusalStore refusalStore = new InMemoryGuardRefusalStore();
    final GuardRefusalService refusals = new GuardRefusalService(this.refusalStore,
            new AreaBanService(this.areaStore, this.areaBans, new DomainEventPublisher(this.transport, this.clock), this.clock),
            new BurstSettings(false, 10, Duration.ofMinutes(5), Duration.ofHours(2)), new SimpleMeterRegistry());
    final IntelLoginCheckService service;

    LoginCheckFixture() {
        IntelClassifier classifier = IntelClassifier.of(Set.of(64500L), List.of(
                new IntelClassifier.ListedPrefix("list:x4bnet-vpn", IntelClass.VPN,
                        IntelPrefix.parseCidr("192.0.2.0/24").orElseThrow())));
        this.holder.replace(classifier);
        IpEnricher enricher = ip -> HOSTING_IP.equals(ip) ? new IpEnricher.IpEnrichment(64500L, "Example Hosting", null)
                : IpEnricher.IpEnrichment.NONE;
        this.service = new IntelLoginCheckService(this.holder, this.policies, enricher, this.hits, this.notices,
                new DomainEventPublisher(this.transport, this.clock), this.clock, new SimpleMeterRegistry(),
                this.areaBans, this.guard, this.owners, this.refusals);
    }

    /** Sets the manual guard level and loads it into the cache, as the transition service would. */
    void guardLevel(GuardLevel level) {
        GuardState before = this.guardStore.current().orElseThrow();
        this.guardStore.compareAndSet(before, GuardState.of(level, GuardLevel.OFF, this.clock.millis()));
        this.guard.reload();
    }

    /** Bans an ASN for everyone, and loads the bans into the cache. */
    void areaBanAsn(long asn) {
        this.areaStore.insert(new AreaBan(UUID.randomUUID(), AreaBan.Kind.ASN, Long.toString(asn), "test ban",
                AreaBan.Source.MANUAL, "admin-oid", this.clock.millis(), null, null, null, null));
        this.areaBans.reload();
    }

    /** Marks an owner as a known player and reloads the owner directory. */
    void known(String owner) {
        this.ownerStore.addKnown(owner);
        this.owners.reload();
    }

    /** Marks an owner as linked staff and reloads the owner directory. */
    void staff(String owner) {
        this.ownerStore.addStaff(owner);
        this.owners.reload();
    }

    /** Stores the policy and the allow-list, then loads them into the in-memory cache as a replica would. */
    void policy(IntelClass classification, IntelPolicy policy) {
        this.store.savePolicy(classification, policy, this.clock.millis());
        this.policies.reload();
    }

    void allowListed(String owner, Long expiresAt) {
        this.store.saveAllowEntry(new IntelAllowEntry(owner, "works at a VPN-using employer", UUID.randomUUID(), expiresAt,
                this.clock.millis()));
        this.policies.reload();
    }

    static LoginCheckDTO.CheckRequest request(String owner, String ip) {
        return new LoginCheckDTO.CheckRequest(owner, ip);
    }
}
