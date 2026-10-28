package net.onelitefeather.blackhole.backend.elo.toxicity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Posts a batch to the inference service: {@code POST /v1/score} with {@code {"texts": [...]}}, answered with
 * {@code {"model_version": "...", "results": [{"overall": 0.12}, ...]}}. The endpoint is fixed by configuration;
 * no caller supplies it.
 */
final class HttpToxicityBatchSender implements ToxicityBatchSender {

    private static final String PATH = "/v1/score";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client;
    private final URI endpoint;

    HttpToxicityBatchSender(URI baseUri, HttpClient client) {
        this.endpoint = baseUri.resolve(PATH);
        this.client = client;
    }

    @Override
    public CompletableFuture<List<Double>> send(List<String> texts) {
        String body;
        try {
            body = mapper.writeValueAsString(Map.of("texts", texts));
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("scorer answered with status " + response.statusCode());
                    }
                    return parseScores(response.body(), texts.size());
                });
    }

    /** Reads the overall scores. Any other shape, a wrong count or a score outside [0, 1] is rejected. */
    static List<Double> parseScores(String body, int expected) {
        JsonNode results;
        try {
            results = new ObjectMapper().readTree(body).path("results");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("scorer returned malformed JSON", e);
        }
        if (!results.isArray() || results.size() != expected) {
            throw new IllegalStateException("scorer returned " + results.size() + " results for " + expected + " texts");
        }
        List<Double> scores = new ArrayList<>(expected);
        for (JsonNode result : results) {
            double overall = result.path("overall").asDouble(-1.0);
            if (!(overall >= 0.0 && overall <= 1.0)) {
                throw new IllegalStateException("scorer returned a score outside [0, 1]");
            }
            scores.add(overall);
        }
        return List.copyOf(scores);
    }
}
