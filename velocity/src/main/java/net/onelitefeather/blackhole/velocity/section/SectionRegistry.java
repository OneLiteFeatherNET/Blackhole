package net.onelitefeather.blackhole.velocity.section;

import net.onelitefeather.blackhole.client.model.EnforcementKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The proxy's view of the backend's punishment sections: loaded from the shared cache (API as
 * fallback) at startup and again whenever the backend announces a section change. Reads always see
 * a complete snapshot; a failed reload keeps the previous one.
 */
public final class SectionRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(SectionRegistry.class);

    /** Where the section list comes from. */
    public interface Source {

        /**
         * @return the list from the shared cache, empty on a miss or when the cache is unavailable
         */
        Optional<List<SectionInfo>> fromCache();

        /**
         * @return the list from the backend API
         * @throws Exception if the backend cannot be reached
         */
        List<SectionInfo> fromApi() throws Exception;
    }

    private final Source source;
    private volatile Map<String, SectionInfo> sections = Map.of();

    public SectionRegistry(Source source) {
        this.source = source;
    }

    /**
     * Loads the sections, preferring the shared cache. If neither source answers the previous
     * snapshot stays in place.
     *
     * @return whether a new snapshot was installed
     */
    public boolean reload() {
        List<SectionInfo> loaded = this.source.fromCache().orElse(null);
        if (loaded == null) {
            try {
                loaded = this.source.fromApi();
            } catch (Exception e) {
                LOGGER.error("Could not load the punishment sections, keeping {} known section(s): {}", this.sections.size(), e.getMessage());
                return false;
            }
        }
        this.sections = loaded.stream().collect(Collectors.toUnmodifiableMap(SectionInfo::key, Function.identity(), (a, b) -> b));
        return true;
    }

    public Optional<SectionInfo> find(String key) {
        return Optional.ofNullable(this.sections.get(key));
    }

    public Collection<SectionInfo> all() {
        return this.sections.values();
    }

    /**
     * @return the sections enforced in one of the given ways
     */
    public List<SectionInfo> ofKind(EnforcementKind... kinds) {
        List<EnforcementKind> wanted = List.of(kinds);
        return this.sections.values().stream().filter(section -> wanted.contains(section.enforcement())).toList();
    }
}
