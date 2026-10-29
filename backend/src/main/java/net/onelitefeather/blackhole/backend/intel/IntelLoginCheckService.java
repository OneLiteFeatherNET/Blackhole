package net.onelitefeather.blackhole.backend.intel;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.intel.IntelClassifier.Match;
import net.onelitefeather.blackhole.backend.intel.IpEnricher.IpEnrichment;
import net.onelitefeather.blackhole.backend.intel.dto.LoginCheckDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives the verdict for one login from in-memory data only: the classifier, the policies and the allow-list are all
 * held by this replica, so the login path reads no database. A VPN or hosting match with a verdict other than ALLOW,
 * or an allow-listed match, is stored as a hit. A FLAG publishes a staff notice that carries the hit id and the class.
 */
@Singleton
public class IntelLoginCheckService {

    static final String FLAGGED_EVENT = "intel.vpn.flagged";
    static final String VERDICT_METER = "blackhole.intel.login_check";
    static final String LATENCY_METER = "blackhole.intel.login_check.latency";
    static final String NOTICE_FAILURE_METER = "blackhole.intel.notice_failures";
    private static final int SOURCE_KEY_MAX = 160;
    private static final Pattern OWNER = Pattern.compile("^[a-fA-F0-9]{128}$");
    private static final Logger LOGGER = LoggerFactory.getLogger(IntelLoginCheckService.class);

    private final IntelClassifierHolder classifier;
    private final IntelPolicyCache policies;
    private final IpEnricher enricher;
    private final IntelHitService hits;
    private final IntelFlagNotices notices;
    private final DomainEventPublisher events;
    private final Clock clock;
    private final MeterRegistry meters;

    public IntelLoginCheckService(
            IntelClassifierHolder classifier,
            IntelPolicyCache policies,
            IpEnricher enricher,
            IntelHitService hits,
            IntelFlagNotices notices,
            DomainEventPublisher events,
            Clock clock,
            MeterRegistry meters
    ) {
        this.classifier = classifier;
        this.policies = policies;
        this.enricher = enricher;
        this.hits = hits;
        this.notices = notices;
        this.events = events;
        this.clock = clock;
        this.meters = meters;
    }

    public LoginCheckDTO check(LoginCheckDTO.CheckRequest request) {
        long started = System.nanoTime();
        try {
            return decide(request);
        } finally {
            Timer.builder(LATENCY_METER).description("Time to answer a login check")
                    .register(this.meters).record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    private LoginCheckDTO decide(LoginCheckDTO.CheckRequest request) {
        if (request.owner() == null || !OWNER.matcher(request.owner()).matches()) {
            return new LoginCheckDTO.Error("owner must be a sha-512 hash", ErrorKind.VALIDATION);
        }
        Optional<InetAddress> address = IpLiteral.parse(request.ip());
        if (address.isEmpty()) {
            return new LoginCheckDTO.Error("ip must be an IPv4 or IPv6 address", ErrorKind.VALIDATION);
        }
        long now = this.clock.millis();
        IpEnrichment enrichment = this.enricher.enrich(request.ip());
        List<Match> matches = this.classifier.get().classify(address.get(), enrichment.asn());
        if (matches.isEmpty()) {
            return count(new LoginCheckDTO.Response(IntelPolicy.ALLOW, null, null), "ALLOW");
        }

        Set<IntelClass> classes = new LinkedHashSet<>();
        Set<String> sources = new LinkedHashSet<>();
        for (Match match : matches) {
            classes.add(match.kind());
            sources.add(match.sourceKey());
        }
        boolean allowListed = this.policies.allowListed(request.owner(), now);
        IntelVerdict verdict = IntelVerdict.decide(this.policies.policies(), classes, allowListed);
        Match deciding = strictest(matches, this.policies);

        if (verdict.policy() != IntelPolicy.ALLOW || allowListed) {
            Optional<UUID> hitId = this.hits.record(new IntelHit(UUID.randomUUID(), request.owner(), request.ip(),
                    join(classes), truncate(String.join(",", sources)), enrichment.asn(), enrichment.asOrg(),
                    verdict.policy(), allowListed, now));
            if (hitId.isPresent() && verdict.policy() == IntelPolicy.FLAG) {
                notify(request.owner(), hitId.get(), deciding.kind(), now);
            }
        }
        return count(new LoginCheckDTO.Response(verdict.policy(), deciding.kind(), deciding.sourceKey()),
                verdict.policy().name());
    }

    /** The match that decided the verdict: the one whose class carries the strictest policy, first on a tie. */
    private static Match strictest(List<Match> matches, IntelPolicyCache policies) {
        Match best = matches.getFirst();
        IntelPolicy bestPolicy = policies.policyOf(best.kind());
        for (Match match : matches) {
            IntelPolicy policy = policies.policyOf(match.kind());
            if (policy.compareTo(bestPolicy) > 0) {
                best = match;
                bestPolicy = policy;
            }
        }
        return best;
    }

    private void notify(String owner, UUID hitId, IntelClass classification, long now) {
        if (!this.notices.claim(owner, now)) {
            return;
        }
        this.events.publish(FLAGGED_EVENT, hitId.toString(),
                Map.of("hitId", hitId.toString(), "classification", classification.name()),
                failure -> {
                    this.meters.counter(NOTICE_FAILURE_METER).increment();
                    LOGGER.warn("A VPN notice for hit {} could not be published", hitId);
                });
    }

    private LoginCheckDTO count(LoginCheckDTO result, String verdictTag) {
        Counter.builder(VERDICT_METER).description("Login checks by verdict").tag("verdict", verdictTag)
                .register(this.meters).increment();
        return result;
    }

    private static String join(Set<IntelClass> classes) {
        return String.join(",", classes.stream().map(Enum::name).toList());
    }

    private static String truncate(String text) {
        return text.length() > SOURCE_KEY_MAX ? text.substring(0, SOURCE_KEY_MAX) : text;
    }
}
