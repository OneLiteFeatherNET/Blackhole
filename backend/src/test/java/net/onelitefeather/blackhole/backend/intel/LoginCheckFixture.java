package net.onelitefeather.blackhole.backend.intel;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.intel.dto.LoginCheckDTO;
import net.onelitefeather.blackhole.backend.support.InMemoryIntelRecordStore;
import net.onelitefeather.blackhole.backend.support.RecordingEventTransport;
import net.onelitefeather.blackhole.backend.support.TestClock;

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
    final IntelLoginCheckService service;

    LoginCheckFixture() {
        IntelClassifier classifier = IntelClassifier.of(Set.of(64500L), List.of(
                new IntelClassifier.ListedPrefix("list:x4bnet-vpn", IntelClass.VPN,
                        IntelPrefix.parseCidr("192.0.2.0/24").orElseThrow())));
        this.holder.replace(classifier);
        IpEnricher enricher = ip -> HOSTING_IP.equals(ip) ? new IpEnricher.IpEnrichment(64500L, "Example Hosting", null)
                : IpEnricher.IpEnrichment.NONE;
        this.service = new IntelLoginCheckService(this.holder, this.policies, enricher, this.hits, this.notices,
                new DomainEventPublisher(this.transport, this.clock), this.clock, new SimpleMeterRegistry());
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
