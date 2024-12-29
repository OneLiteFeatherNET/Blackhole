package net.onelitefeather.blackhole.request.template;

import com.fasterxml.jackson.databind.type.TypeFactory;
import net.onelitefeather.blackhole.api.template.PunishTemplate;
import net.onelitefeather.blackhole.request.BaseWebRequest;
import net.onelitefeather.blackhole.util.GenericBodyHandler;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class PunishTemplateRequests extends BaseWebRequest<PunishTemplate> implements TemplateWebRequests {

    private static final GenericBodyHandler<List<PunishTemplate>> LIST_TEMPLATE_HANDLER = new GenericBodyHandler<>(TypeFactory.defaultInstance().constructArrayType(TypeFactory.defaultInstance().constructType(PunishTemplate.class)));
    private static final GenericBodyHandler<PunishTemplate> SINGLE_TEMPLATE_HANDLER = new GenericBodyHandler<>(TypeFactory.defaultInstance().constructType(PunishTemplate.class));

    public PunishTemplateRequests(@NotNull String baseUrl, @NotNull HttpClient httpClient) {
        super(baseUrl, httpClient);
    }

    @Override
    public @NotNull CompletableFuture<PunishTemplate> addAsync(@NotNull PunishTemplate template) {
        String templateJson = mapObjectToString(template);
        HttpRequest addRequest = HttpRequest.newBuilder()
                .uri(URI.create(buildUrl(TEMPLATE_BASE_URL + "/")))
                .POST(HttpRequest.BodyPublishers.ofString(templateJson))
                .build();
        CompletableFuture<HttpResponse<PunishTemplate>> addResponse =
                this.httpClient.sendAsync(addRequest, SINGLE_TEMPLATE_HANDLER);
        return addResponse.thenApply(HttpResponse::body);
    }

    @Override
    public @NotNull CompletableFuture<PunishTemplate> updateAsync(@NotNull PunishTemplate template) {
        String templateJson = mapObjectToString(template);
        HttpRequest addRequest = HttpRequest.newBuilder()
                .uri(URI.create(buildUrl(TEMPLATE_BASE_URL + "/update")))
                .POST(HttpRequest.BodyPublishers.ofString(templateJson))
                .build();
        CompletableFuture<HttpResponse<PunishTemplate>> addResponse =
                this.httpClient.sendAsync(addRequest, SINGLE_TEMPLATE_HANDLER);
        return addResponse.thenApply(HttpResponse::body);
    }

    @Override
    public @NotNull CompletableFuture<PunishTemplate> deleteAsync(@NotNull UUID identifier) {
        HttpRequest deleteRequest = HttpRequest.newBuilder()
                .uri(URI.create(buildUrl(TEMPLATE_BASE_URL + "/delete/" + identifier)))
                .DELETE()
                .build();
        CompletableFuture<HttpResponse<PunishTemplate>> deleteResponse =
                this.httpClient.sendAsync(deleteRequest, SINGLE_TEMPLATE_HANDLER);
        return deleteResponse.thenApply(HttpResponse::body);
    }

    @Override
    public @NotNull CompletableFuture<List<PunishTemplate>> getAllAsync() {
        HttpRequest getAllRequest = HttpRequest.newBuilder()
                .uri(URI.create(buildUrl(TEMPLATE_BASE_URL + "/all")))
                .GET()
                .build();

        CompletableFuture<HttpResponse<List<PunishTemplate>>> completableFuture =
                this.httpClient.sendAsync(getAllRequest, LIST_TEMPLATE_HANDLER);
        return completableFuture.thenApply(HttpResponse::body);
    }

    @Override
    public @NotNull CompletableFuture<Optional<PunishTemplate>> getAsync(@NotNull UUID identifier) {
        HttpRequest getRequest = HttpRequest.newBuilder()
                .uri(URI.create(buildUrl(TEMPLATE_BASE_URL + "/" + identifier)))
                .GET()
                .build();

        CompletableFuture<HttpResponse<PunishTemplate>> completableFuture =
                this.httpClient.sendAsync(getRequest, SINGLE_TEMPLATE_HANDLER);
        return completableFuture
                .thenApply(HttpResponse::body)
                .thenApply(Optional::ofNullable);
    }
}
