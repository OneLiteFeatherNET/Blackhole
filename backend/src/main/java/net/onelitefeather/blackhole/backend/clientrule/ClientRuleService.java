package net.onelitefeather.blackhole.backend.clientrule;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.clientrule.dto.ClientRuleDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Owns the client rule registry: validated CRUD, the cached copy proxies read, and the change
 * announcement that makes them reload. Expected failures are returned as {@link ClientRuleDTO.Error}.
 * Rules are evaluated on the proxy, never here.
 */
@Singleton
public class ClientRuleService {

    private final ClientRuleStore store;
    private final ClientRuleReferences references;
    private final ClientRuleCache cache;
    private final ClientRuleChangeNotifier notifier;
    private final Clock clock;

    public ClientRuleService(
            ClientRuleStore store,
            ClientRuleReferences references,
            ClientRuleCache cache,
            ClientRuleChangeNotifier notifier,
            Clock clock
    ) {
        this.store = store;
        this.references = references;
        this.cache = cache;
        this.notifier = notifier;
        this.clock = clock;
    }

    /**
     * Creates a rule, enabled.
     *
     * @param author the verified staff member, written as {@code recorded_by_oid}
     * @return the stored {@link ClientRuleDTO.Response}, or an {@link ClientRuleDTO.Error}: validation
     * (400) for a bad definition or an unknown section or template, conflict (409) for a taken key
     */
    public ClientRuleDTO create(ClientRuleDTO.CreateRequest request, UUID author) {
        ClientRuleSpec spec = request.toSpec();
        ClientRuleDTO.Error problem = problemWith(spec);
        if (problem != null) {
            return problem;
        }
        ClientRuleStore.Write written = this.store.insert(spec, author, this.clock.millis());
        return switch (written) {
            case TAKEN -> new ClientRuleDTO.Error("a rule with key '" + spec.key() + "' already exists", ErrorKind.CONFLICT);
            case MISSING_TEMPLATE -> missingTemplate(spec.templateKey());
            default -> changed(spec.key());
        };
    }

    /**
     * Replaces every editable field of an existing rule, including whether it is enabled. The hit
     * count and creation time stay.
     *
     * @param key the key from the path; the body must name the same rule
     * @return the stored {@link ClientRuleDTO.Response}, or an {@link ClientRuleDTO.Error}: not found
     * (404), validation (400)
     */
    public ClientRuleDTO update(String key, ClientRuleDTO.UpdateRequest request, UUID author) {
        if (this.store.find(key).isEmpty()) {
            return notFound(key);
        }
        if (!key.equals(request.key())) {
            return new ClientRuleDTO.Error("the key in the body ('" + request.key() + "') must equal the key in the path ('" + key + "')", ErrorKind.VALIDATION);
        }
        ClientRuleSpec spec = request.toSpec();
        ClientRuleDTO.Error problem = problemWith(spec);
        if (problem != null) {
            return problem;
        }
        return switch (this.store.update(spec, author, this.clock.millis())) {
            case ABSENT -> notFound(key);
            case MISSING_TEMPLATE -> missingTemplate(spec.templateKey());
            default -> changed(key);
        };
    }

    /**
     * Deletes a rule with its patterns, servers and sections.
     *
     * @return the removed rule, or a not found error
     */
    public ClientRuleDTO delete(String key) {
        ClientRuleStore.Stored stored = this.store.find(key).orElse(null);
        if (stored == null || !this.store.delete(key)) {
            return notFound(key);
        }
        this.cache.evict();
        this.notifier.changed(key);
        return toResponse(stored);
    }

    /** @return the rule, or a not found error */
    public ClientRuleDTO get(String key) {
        return this.store.find(key).<ClientRuleDTO>map(ClientRuleService::toResponse).orElseGet(() -> notFound(key));
    }

    /**
     * Every rule, enabled or not, sorted by key. Also refreshes the enabled rules in the shared cache,
     * which is where proxies read them and call this endpoint only when it is missing.
     */
    public List<ClientRuleDTO.Response> list() {
        List<ClientRuleDTO.Response> all = this.store.list().stream().map(ClientRuleService::toResponse).toList();
        this.cache.putEnabled(all.stream().filter(ClientRuleDTO.Response::enabled).toList());
        return all;
    }

    private ClientRuleDTO.Error problemWith(ClientRuleSpec spec) {
        List<String> errors = new ArrayList<>(ClientRuleValidator.validate(spec));
        if (errors.isEmpty()) {
            for (String sectionKey : spec.sectionKeys()) {
                if (!this.references.serverSectionExists(sectionKey)) {
                    errors.add("sectionKeys: there is no SERVER section '" + sectionKey + "'");
                }
            }
            if (spec.templateKey() != null && !this.references.templateExists(spec.templateKey())) {
                errors.add("templateKey: there is no template '" + spec.templateKey() + "'");
            }
        }
        return errors.isEmpty() ? null : new ClientRuleDTO.Error(String.join("; ", errors), ErrorKind.VALIDATION);
    }

    private ClientRuleDTO changed(String key) {
        this.cache.evict();
        this.notifier.changed(key);
        return this.store.find(key).<ClientRuleDTO>map(ClientRuleService::toResponse).orElseGet(() -> notFound(key));
    }

    private static ClientRuleDTO.Error notFound(String key) {
        return new ClientRuleDTO.Error("there is no rule with key '" + key + "'", ErrorKind.NOT_FOUND);
    }

    private static ClientRuleDTO.Error missingTemplate(String templateKey) {
        return new ClientRuleDTO.Error("templateKey: there is no template '" + templateKey + "'", ErrorKind.VALIDATION);
    }

    static ClientRuleDTO.Response toResponse(ClientRuleStore.Stored stored) {
        ClientRuleSpec spec = stored.spec();
        return new ClientRuleDTO.Response(spec.key(), spec.displayName(), spec.kind(), spec.enabled(), spec.patterns(), spec.protocolMin(),
                spec.protocolMax(), spec.negate(), spec.scope(), spec.sectionKeys(), spec.targetKind(), spec.servers(), spec.action(),
                spec.denyMessage(), spec.templateKey(), spec.acknowledgedSpoofable(), spec.hideCommand(), stored.hitCount(),
                Instant.ofEpochMilli(stored.createdAt()), Instant.ofEpochMilli(stored.updatedAt()));
    }
}
