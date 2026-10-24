package net.onelitefeather.blackhole.velocity.section;

import net.onelitefeather.blackhole.client.api.SectionApi;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.invoker.ApiException;

import java.util.List;

/**
 * {@link SectionRegistry.Source} backed by the backend's section API.
 */
public final class BackendSectionSource implements SectionRegistry.Source {

    private final SectionApi sectionApi;

    public BackendSectionSource(ApiClient client) {
        this.sectionApi = new SectionApi(client);
    }

    @Override
    public List<SectionInfo> load() throws ApiException {
        return this.sectionApi.listSections().stream().map(SectionInfo::of).toList();
    }
}
