package net.onelitefeather.blackhole.backend.clientrule;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.clientrule.dto.ClientRuleHitDTO;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.utils.OwnerHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Takes in the hits the proxy reports: stores them without any client trait, counts them on the rule,
 * announces them, and applies the rule's template when that is the action. The proxy decides on its
 * own whether to deny; the backend never evaluates a rule.
 *
 * <p>A template is applied at most once while the player has an active punishment from the same rule
 * (the applier decides), never for a bypassed player, and never when the backend cannot apply it:
 * the hit is still stored then.</p>
 */
@Singleton
public class ClientRuleHitService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientRuleHitService.class);

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;

    private static final Pattern SERVER_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private final ClientRuleHitStore hits;
    private final ClientRuleTemplateApplier templates;
    private final DomainEventPublisher events;
    private final ClientRuleStore rules;
    private final Clock clock;

    public ClientRuleHitService(ClientRuleHitStore hits, ClientRuleTemplateApplier templates, DomainEventPublisher events,
                                ClientRuleStore rules, Clock clock) {
        this.hits = hits;
        this.templates = templates;
        this.events = events;
        this.rules = rules;
        this.clock = clock;
    }

    /**
     * Records a hit.
     *
     * @return {@link ClientRuleHitDTO.Recorded}, or an {@link ClientRuleHitDTO.Error}: validation (400) for a
     * malformed owner or server, not found (404) for an unknown or disabled rule - nothing is stored then
     */
    public ClientRuleHitDTO record(ClientRuleHitDTO.CreateRequest request) {
        if (!OwnerHash.isValid(request.owner())) {
            return new ClientRuleHitDTO.Error("owner must be a sha-512 hash", ErrorKind.VALIDATION);
        }
        if (request.server() != null && !SERVER_NAME.matcher(request.server()).matches()) {
            return new ClientRuleHitDTO.Error("server must be 1-64 characters of A-Z a-z 0-9 _ . -", ErrorKind.VALIDATION);
        }
        long at = this.clock.millis();
        Optional<ClientRuleHitStore.RecordedHit> recorded = this.hits.record(
                request.rule(), request.owner(), request.server(), request.enforced(), request.bypassed(), at);
        if (recorded.isEmpty()) {
            return new ClientRuleHitDTO.Error("there is no enabled rule with key '" + request.rule() + "'", ErrorKind.NOT_FOUND);
        }
        ClientRuleHitStore.RecordedHit rule = recorded.get();
        this.events.publish("client-rule.hit", Map.of(
                "rule", rule.ruleKey(), "owner", request.owner(), "kind", rule.kind().name(), "action", rule.action().name(), "at", at));
        ClientRuleTemplateApplier.Result result = applyTemplate(rule, request);
        return new ClientRuleHitDTO.Recorded(rule.ruleKey(), rule.action(),
                result.outcome() == ClientRuleTemplateApplier.Outcome.APPLIED, result.punishmentIdentifier());
    }

    /** The newest hits of a rule. */
    public ClientRuleHitDTO ofRule(String ruleKey, Integer limit) {
        if (this.rules.find(ruleKey).isEmpty()) {
            return new ClientRuleHitDTO.Error("there is no rule with key '" + ruleKey + "'", ErrorKind.NOT_FOUND);
        }
        return view(this.hits.byRule(ruleKey, clamp(limit)));
    }

    /** The newest hits of a player. */
    public ClientRuleHitDTO ofOwner(String owner, Integer limit) {
        if (!OwnerHash.isValid(owner)) {
            return new ClientRuleHitDTO.Error("owner must be a sha-512 hash", ErrorKind.VALIDATION);
        }
        return view(this.hits.byOwner(owner, clamp(limit)));
    }

    private ClientRuleTemplateApplier.Result applyTemplate(ClientRuleHitStore.RecordedHit rule, ClientRuleHitDTO.CreateRequest request) {
        ClientRuleTemplateApplier.Result none = new ClientRuleTemplateApplier.Result(ClientRuleTemplateApplier.Outcome.NOT_APPLIED, null);
        if (rule.action() != ClientRuleAction.TEMPLATE || request.bypassed() || rule.templateKey() == null) {
            return none;
        }
        try {
            return this.templates.applyForRule(request.owner(), rule.templateKey(), rule.ruleKey());
        } catch (RuntimeException e) {
            // Fail open: the hit stands, no punishment is applied, and the proxy reports the next hit again.
            LOGGER.warn("Could not apply the template of client rule {}: {}", rule.ruleKey(), e.getMessage());
            return none;
        }
    }

    private static int clamp(Integer limit) {
        return limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
    }

    private static ClientRuleHitDTO view(List<ClientRuleHitStore.Hit> found) {
        return new ClientRuleHitDTO.Hits(found.stream().map(hit -> new ClientRuleHitDTO.View(
                hit.ruleKey(), hit.owner(), Instant.ofEpochMilli(hit.at()), hit.action(), hit.enforced(), hit.bypassed(), hit.server())).toList());
    }
}
