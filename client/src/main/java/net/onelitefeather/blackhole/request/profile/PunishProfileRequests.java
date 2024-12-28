package net.onelitefeather.blackhole.request.profile;

import com.fasterxml.jackson.databind.type.TypeFactory;
import net.onelitefeather.blackhole.api.profile.PunishProfile;
import net.onelitefeather.blackhole.request.BaseWebRequest;
import net.onelitefeather.blackhole.util.GenericBodyHandler;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class PunishProfileRequests extends BaseWebRequest<PunishProfile> implements ProfileWebRequests {

    private static final GenericBodyHandler<List<PunishProfile>> LIST_PROFILE_HANDLER = new GenericBodyHandler<>(TypeFactory.defaultInstance().constructArrayType(TypeFactory.defaultInstance().constructType(PunishProfile.class)));
    private static final GenericBodyHandler<PunishProfile> SINGLE_PROFILE_HANDLER = new GenericBodyHandler<>(TypeFactory.defaultInstance().constructType(PunishProfile.class));

    /**
     * Create a new instance of the PunishProfileRequests.
     *
     * @param baseUrl    the base URL of the server
     * @param httpClient the HTTP client
     */
    public PunishProfileRequests(@NotNull String baseUrl, @NotNull HttpClient httpClient) {
        super(baseUrl, httpClient);
    }

    @Override
    public @NotNull PunishProfile add(@NotNull PunishProfile profile) {
        String profileJson = mapObjectToString(profile);
        HttpRequest addRequest = HttpRequest.newBuilder()
                .uri(URI.create(buildUrl(PROFILE_BASE_URL + "/")))
                .POST(HttpRequest.BodyPublishers.ofString(profileJson))
                .build();
        CompletableFuture<HttpResponse<PunishProfile>> addResponse =
                this.httpClient.sendAsync(addRequest, SINGLE_PROFILE_HANDLER);
        return addResponse.thenApply(HttpResponse::body).join();
    }

    @Override
    public @NotNull Optional<PunishProfile> get(@NotNull String owner) {
        HttpRequest getRequest = HttpRequest.newBuilder()
                .uri(URI.create(buildUrl(PROFILE_BASE_URL + "/" + owner)))
                .GET()
                .build();
        CompletableFuture<HttpResponse<PunishProfile>> getResponse =
                this.httpClient.sendAsync(getRequest, SINGLE_PROFILE_HANDLER);
        return Optional.ofNullable(getResponse.thenApply(HttpResponse::body).join());
    }

    @Override
    public @NotNull PunishProfile update(@NotNull String owner, @NotNull PunishProfile profile) {
        String profileJson = mapObjectToString(profile);
        HttpRequest updateRequest = HttpRequest.newBuilder()
                .uri(URI.create(buildUrl(PROFILE_BASE_URL + "/update/" + owner)))
                .POST(HttpRequest.BodyPublishers.ofString(profileJson))
                .build();
        CompletableFuture<HttpResponse<PunishProfile>> updateResponse =
                this.httpClient.sendAsync(updateRequest, SINGLE_PROFILE_HANDLER);
        return updateResponse.thenApply(HttpResponse::body).join();
    }

    @Override
    public @NotNull PunishProfile delete(@NotNull String owner) {
        HttpRequest deleteRequest = HttpRequest.newBuilder()
                .uri(URI.create(buildUrl(PROFILE_BASE_URL + "/delete/" + owner)))
                .DELETE()
                .build();
        CompletableFuture<HttpResponse<PunishProfile>> deleteResponse =
                this.httpClient.sendAsync(deleteRequest, SINGLE_PROFILE_HANDLER);
        return deleteResponse.thenApply(HttpResponse::body).join();
    }

    @Override
    public @NotNull List<PunishProfile> getAll() {
        HttpRequest getAllRequest = HttpRequest.newBuilder()
                .uri(URI.create(buildUrl(PROFILE_BASE_URL + "/all")))
                .GET()
                .build();
        CompletableFuture<HttpResponse<List<PunishProfile>>> completableFuture =
                this.httpClient.sendAsync(getAllRequest, LIST_PROFILE_HANDLER);
        return completableFuture.thenApply(HttpResponse::body).join();
    }
}
