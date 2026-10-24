package net.onelitefeather.blackhole.velocity.clientrule;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * The proxy's in-memory copy of the enabled client rules: loaded from the shared cache (API as
 * fallback) at start and again whenever the backend announces a change. Reads always see a complete
 * snapshot; a failed reload keeps the previous one, and with no snapshot at all nothing is enforced.
 */
public final class RuleRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuleRegistry.class);

    /** Where the rules come from. */
    public interface Source {

        /** @return the enabled rules from the shared cache, empty on a miss or when the cache is unavailable */
        Optional<List<ClientRule>> fromCache();

        /**
         * @return the enabled rules from the backend API; empty when the backend does not know the routes yet
         * @throws Exception if the backend cannot be reached
         */
        List<ClientRule> fromApi() throws Exception;
    }

    private final Source source;
    private volatile List<ClientRule> rules = List.of();
    private volatile boolean loaded;
    private boolean warned;

    public RuleRegistry(@NotNull Source source) {
        this.source = source;
    }

    /**
     * Loads the rules, preferring the shared cache. If neither source answers, the previous snapshot stays
     * (and with none yet, nothing is enforced; one warning says so).
     *
     * @return whether a new snapshot was installed
     */
    public synchronized boolean reload() {
        List<ClientRule> loadedRules = this.source.fromCache().orElse(null);
        if (loadedRules == null) {
            try {
                loadedRules = this.source.fromApi();
            } catch (Exception e) {
                if (!this.loaded && !this.warned) {
                    this.warned = true;
                    LOGGER.warn("Could not load the client rules, none are enforced until the backend answers: {}", e.getMessage());
                } else {
                    LOGGER.debug("Could not reload the client rules, keeping {} known rule(s): {}", this.rules.size(), e.getMessage());
                }
                return false;
            }
        }
        this.rules = List.copyOf(loadedRules);
        this.loaded = true;
        return true;
    }

    /** @return the enabled rules of the current snapshot, empty before the first successful load */
    public @NotNull List<ClientRule> rules() {
        return this.rules;
    }

    /** @return whether a snapshot was ever installed */
    public boolean loaded() {
        return this.loaded;
    }
}
