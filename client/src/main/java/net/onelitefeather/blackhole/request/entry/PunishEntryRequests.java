package net.onelitefeather.blackhole.request.entry;

import com.fasterxml.jackson.databind.type.TypeFactory;
import net.onelitefeather.blackhole.api.profile.PunishProfile;
import net.onelitefeather.blackhole.api.punish.PunishEntry;
import net.onelitefeather.blackhole.request.BaseWebRequest;
import net.onelitefeather.blackhole.util.GenericBodyHandler;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class PunishEntryRequests extends BaseWebRequest<PunishEntry> implements PunishWebRequests {

    private static final GenericBodyHandler<List<PunishEntry>> LIST_PUNISH_HANDLER = new GenericBodyHandler<>(TypeFactory.defaultInstance().constructArrayType(TypeFactory.defaultInstance().constructType(PunishEntry.class)));
    private static final GenericBodyHandler<PunishProfile> PROFILE_BODY_HANDLER = new GenericBodyHandler<>(TypeFactory.defaultInstance().constructType(PunishProfile.class));

    /**
     * Create a new instance of the BaseWebRequest.
     *
     * @param baseUrl    the base URL of the server
     * @param httpClient the HTTP client
     */
    public PunishEntryRequests(@NotNull String baseUrl, @NotNull HttpClient httpClient) {
        super(baseUrl, httpClient);
    }

    @Override
    public @NotNull PunishProfile add(@NotNull String owner, @NotNull UUID templateId, @NotNull UUID source) {
        String url = buildUrl(ENTRY_BASE_URL + "/active/" + owner + "/" + templateId + "/" + source);
        HttpRequest addRequest = HttpRequest
                .newBuilder(URI.create(url))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        CompletableFuture<HttpResponse<PunishProfile>> addResponse =
                this.httpClient.sendAsync(addRequest, PROFILE_BODY_HANDLER);
        return addResponse.thenApply(HttpResponse::body).join();
    }

    @Override
    public @NotNull List<PunishEntry> getAll(int page, int pageSize) {
        HttpRequest getRequest = HttpRequest.newBuilder()
                .uri(URI.create(buildUrl(ENTRY_BASE_URL + "/all?page=" + page + "&pageSize=" + pageSize)))
                .GET()
                .build();
        CompletableFuture<HttpResponse<List<PunishEntry>>> getResponse =
                this.httpClient.sendAsync(getRequest, LIST_PUNISH_HANDLER);
        return getResponse.thenApply(HttpResponse::body).join();
    }
}
