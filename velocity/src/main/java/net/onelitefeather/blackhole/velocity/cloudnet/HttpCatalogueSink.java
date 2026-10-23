package net.onelitefeather.blackhole.velocity.cloudnet;

import net.onelitefeather.blackhole.client.api.CloudNetApi;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.model.CatalogueReportRequest;
import net.onelitefeather.blackhole.client.model.CatalogueServiceReport;
import net.onelitefeather.blackhole.client.model.CatalogueTask;

import java.util.List;

/** Sends the catalogue with {@code PUT /cloudnet/catalogue}. */
public final class HttpCatalogueSink implements CatalogueSink {

    private final CloudNetApi api;

    public HttpCatalogueSink(ApiClient client) {
        this.api = new CloudNetApi(client);
    }

    @Override
    public void report(String proxyId, Catalogue catalogue) throws Exception {
        this.api.reportCloudNetCatalogue(new CatalogueReportRequest()
                .proxyId(proxyId)
                .tasks(catalogue.tasks().stream().map(task -> new CatalogueTask().name(task.name()).groups(sorted(task.groups()))).toList())
                .groups(catalogue.groups())
                .services(catalogue.services().stream()
                        .map(service -> new CatalogueServiceReport().name(service.name()).task(service.task())
                                .groups(sorted(service.groups())).running(service.running()))
                        .toList()));
    }

    private static List<String> sorted(java.util.Set<String> names) {
        return names.stream().sorted().toList();
    }
}
