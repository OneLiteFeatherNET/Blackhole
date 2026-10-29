package net.onelitefeather.blackhole.backend.firewall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * OPNsense adapter of {@link AliasGateway}, over its REST API. The base URL is validated before any request: https,
 * or plain http only to a loopback host, no user information, no query. Redirects are not followed. The key and
 * secret are sent as HTTP basic credentials and are never logged or put into an exception message.
 *
 * <p>The request bodies follow the community examples ({@code {"address": ...}}); the spike against a lab firewall
 * (openspec add-ip-bans-via-firewall, task 0.1) is still to be recorded in the change.</p>
 */
@Singleton
public class OpnsenseAliasClient implements AliasGateway {

    private static final Pattern SPLIT = Pattern.compile("[\\s,]+");
    private static final String ALIAS_UTIL = "/api/firewall/alias_util/";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;
    private final URI base;
    private final String authorization;
    private final Duration timeout;

    /**
     * @param baseUrl   the firewall's base URL, empty while the firewall layer is off
     * @param apiKey    the API key, empty when none is configured
     * @param apiSecret the API secret, empty when none is configured
     * @param timeout   the timeout of one request
     */
    public OpnsenseAliasClient(@Value("${blackhole.ipban.firewall.base-url:}") String baseUrl,
                               @Value("${blackhole.ipban.firewall.api-key:}") String apiKey,
                               @Value("${blackhole.ipban.firewall.api-secret:}") String apiSecret,
                               @Value("${blackhole.ipban.firewall.timeout:PT10S}") Duration timeout) {
        this.base = baseUrl == null || baseUrl.isBlank() ? null : validateBaseUrl(baseUrl.strip());
        this.authorization = apiKey == null || apiKey.isBlank() ? null
                : "Basic " + Base64.getEncoder().encodeToString((apiKey + ":" + (apiSecret == null ? "" : apiSecret))
                .getBytes(StandardCharsets.UTF_8));
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /**
     * @return the base URL as a URI
     * @throws IllegalStateException when the URL is not https, nor http to a loopback host, or carries user information
     */
    static URI validateBaseUrl(String text) {
        URI uri;
        try {
            uri = URI.create(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("blackhole.ipban.firewall.base-url is not a URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException("blackhole.ipban.firewall.base-url must name a host without user information, query or fragment");
        }
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
        if (!"https".equals(scheme) && !("http".equals(scheme) && loopback)) {
            throw new IllegalStateException("blackhole.ipban.firewall.base-url must be https (plain http only for a loopback host)");
        }
        return uri;
    }

    @Override
    public Set<String> entries(String alias) {
        JsonNode root = parse(send(get(ALIAS_UTIL + "list/" + alias), "list"), "list");
        Set<String> entries = new HashSet<>();
        JsonNode list = root.isObject() && root.has("address") ? root.get("address") : root;
        if (list.isArray()) {
            list.forEach(node -> addIfText(entries, node));
        } else if (list.isTextual()) {
            for (String part : SPLIT.split(list.asText().strip())) {
                addIfText(entries, part);
            }
        } else {
            throw new AliasException("list", "the firewall answered the alias list in an unexpected shape");
        }
        return entries;
    }

    @Override
    public void add(String alias, String range) {
        checkResult(send(post(ALIAS_UTIL + "add/" + alias, range), "add"), "add");
    }

    @Override
    public void delete(String alias, String range) {
        checkResult(send(post(ALIAS_UTIL + "delete/" + alias, range), "delete"), "delete");
    }

    @Override
    public void reconfigure() {
        send(post("/api/firewall/alias/reconfigure", null), "reconfigure");
    }

    private HttpRequest get(String path) {
        return builder(path).GET().build();
    }

    private HttpRequest post(String path, String range) {
        String body;
        try {
            body = range == null ? "{}" : this.json.writeValueAsString(Map.of("address", range));
        } catch (IOException e) {
            throw new AliasException("add", "the request could not be written");
        }
        return builder(path).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private HttpRequest.Builder builder(String path) {
        if (this.base == null) {
            throw new AliasException("list", "the firewall layer has no base URL configured");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(this.base.resolve(path)).timeout(this.timeout)
                .header("Accept", "application/json");
        if (this.authorization != null) {
            builder.header("Authorization", this.authorization);
        }
        return builder;
    }

    private String send(HttpRequest request, String operation) {
        HttpResponse<String> response;
        try {
            response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new AliasException(operation, "the firewall could not be reached");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AliasException(operation, "the firewall request was interrupted");
        }
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new AliasException(operation, "the firewall refused the credentials; the request is not retried in a loop");
        }
        if (status < 200 || status >= 300) {
            throw new AliasException(operation, "the firewall answered with status " + status);
        }
        return response.body();
    }

    private JsonNode parse(String body, String operation) {
        try {
            return this.json.readTree(body);
        } catch (IOException | NullPointerException e) {
            throw new AliasException(operation, "the firewall answered with something that is not JSON");
        }
    }

    /**
     * An answer that reports {@code "result": "failed"} is a failed write, even with a 200 status. An answer that is
     * empty or not JSON is taken as success, because the status code already said it was accepted.
     */
    private void checkResult(String body, String operation) {
        if (body == null || body.isBlank()) {
            return;
        }
        try {
            JsonNode root = this.json.readTree(body);
            if (root != null && root.isObject() && root.has("result") && "failed".equalsIgnoreCase(root.get("result").asText())) {
                throw new AliasException(operation, "the firewall did not apply the change");
            }
        } catch (IOException e) {
            // not JSON: the status code is the answer
        }
    }

    private static void addIfText(Set<String> entries, JsonNode node) {
        if (node.isTextual()) {
            addIfText(entries, node.asText());
        }
    }

    private static void addIfText(Set<String> entries, String text) {
        String stripped = text.strip();
        if (!stripped.isEmpty()) {
            entries.add(stripped);
        }
    }
}
