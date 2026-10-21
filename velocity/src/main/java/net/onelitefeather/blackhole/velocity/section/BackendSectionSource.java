package net.onelitefeather.blackhole.velocity.section;

import net.onelitefeather.blackhole.client.api.SectionApi;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.velocity.redis.RedisSyncService;

import java.util.List;
import java.util.Optional;

/**
 * {@link SectionRegistry.Source} backed by the backend's shared cache (read through Redis) and its
 * section API.
 */
public final class BackendSectionSource implements SectionRegistry.Source {

    private final RedisSyncService redis;
    private final SectionApi sectionApi;

    public BackendSectionSource(RedisSyncService redis, ApiClient client) {
        this.redis = redis;
        this.sectionApi = new SectionApi(client);
    }

    @Override
    public Optional<List<SectionInfo>> fromCache() {
        return this.redis.readCachedSections();
    }

    @Override
    public List<SectionInfo> fromApi() throws ApiException {
        return this.sectionApi.listSections().stream().map(SectionInfo::of).toList();
    }
}
