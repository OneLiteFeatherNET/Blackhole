package net.onelitefeather.blackhole.velocity.redis;

import net.onelitefeather.blackhole.client.model.PunishTemplateResponse;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * The backend's full punishment template list as stored in the shared cache under
 * {@link RedisTopology#TEMPLATES_ALL_KEY}: the JSON array of template responses the backend writes
 * whenever it lists templates. Used for tab completion and the template lookup of {@code /punish}.
 *
 * <p>Every way the cache can fail to answer - a miss, an error or timeout, a payload that is not a
 * template list - reads as empty, so the caller falls back to the API, which repopulates the key.</p>
 */
public final class CachedTemplates {

    private static final Logger LOGGER = LoggerFactory.getLogger(CachedTemplates.class);

    private final SharedCacheReader cache;
    private final SyncCodec codec = new SyncCodec();

    public CachedTemplates(@NotNull SharedCacheReader cache) {
        this.cache = cache;
    }

    /**
     * @return the cached templates; empty when the cache cannot be trusted to answer
     */
    public Optional<List<PunishTemplateResponse>> read() {
        Optional<String> stored;
        try {
            stored = this.cache.readShared(RedisTopology.TEMPLATES_ALL_KEY);
        } catch (RuntimeException e) {
            LOGGER.warn("Reading the cached templates failed, falling back to the API: {}", e.getMessage());
            return Optional.empty();
        }
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        Optional<List<PunishTemplateResponse>> templates = this.codec.templates(stored.get());
        if (templates.isEmpty()) {
            LOGGER.warn("The cached template list is unreadable, falling back to the API");
        }
        return templates;
    }
}
