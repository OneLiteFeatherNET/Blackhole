package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.PunishTemplateResponse;
import net.onelitefeather.blackhole.velocity.auth.FakeHttpClient;
import net.onelitefeather.blackhole.velocity.auth.TokenSource;
import net.onelitefeather.blackhole.velocity.config.BlackholeApiClients;
import net.onelitefeather.blackhole.velocity.redis.CachedTemplates;
import net.onelitefeather.blackhole.velocity.redis.SharedCacheReader;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Templates come from the shared cache when it holds a list; otherwise the gateway lists them through
 * the API, which answers with a page object (the templates under {@code content}), not a bare array.
 */
class HttpBackendGatewayTemplatesTest {

    private static final TokenSource TOKEN = new TokenSource() {
        @Override
        public String token() {
            return "proxy-token";
        }

        @Override
        public void rejected(String token) {
        }
    };

    private static final String PAGE = "{\"content\":[{\"identifier\":\"6c0b4a52-1a39-4b0e-8e7c-0d2f1a9b3c11\",\"key\":\"hacking\","
            + "\"reason\":\"Hacking\",\"section\":\"network\",\"steps\":[{\"duration\":\"P1D\",\"eloCost\":100}]}],"
            + "\"pageable\":{\"number\":0,\"sort\":{\"orderBy\":[]},\"size\":100,\"mode\":\"OFFSET\"},"
            + "\"totalSize\":1,\"totalPages\":1,\"empty\":false,\"size\":100,\"offset\":0,\"pageNumber\":0,\"numberOfElements\":1}";

    private static final String CACHED = "[{\"identifier\":\"0a1b2c3d-1a39-4b0e-8e7c-0d2f1a9b3c22\",\"key\":\"cached-spam\","
            + "\"reason\":\"Spam\",\"section\":\"chat\",\"steps\":[{\"duration\":\"PT1H\",\"eloCost\":10}]}]";

    private static HttpBackendGateway gateway(FakeHttpClient transport, SharedCacheReader cache) {
        ApiClient client = BlackholeApiClients.create("http://backend.test", TOKEN, transport);
        return new HttpBackendGateway(client, new CachedTemplates(cache), Clock.systemUTC());
    }

    private static SharedCacheReader miss() {
        return key -> Optional.empty();
    }

    @Test
    void aCachedListIsUsedWithoutCallingTheApi() throws Exception {
        FakeHttpClient transport = new FakeHttpClient().answer(500, "{}");

        List<PunishTemplateResponse> templates = gateway(transport, key -> Optional.of(CACHED)).templates();

        assertEquals("cached-spam", templates.getFirst().getKey());
        assertTrue(transport.requests().isEmpty(), "a cache hit needs no request: " + transport.requests());
    }

    @Test
    void aMissFallsBackToTheApiAndParsesItsPage() throws Exception {
        FakeHttpClient transport = new FakeHttpClient().answer(200, PAGE);

        List<PunishTemplateResponse> templates = gateway(transport, miss()).templates();

        assertEquals(1, templates.size(), "the one template on the page");
        assertEquals("hacking", templates.getFirst().getKey());
        assertEquals(100, templates.getFirst().getSteps().getFirst().getEloCost());
        assertEquals(1, transport.requests().size(), "one API call");
    }

    @Test
    void aCorruptCachedListFallsBackToTheApi() throws Exception {
        FakeHttpClient transport = new FakeHttpClient().answer(200, PAGE);

        List<PunishTemplateResponse> templates = gateway(transport, key -> Optional.of("{broken")).templates();

        assertEquals("hacking", templates.getFirst().getKey());
    }

    @Test
    void aFailingCacheFallsBackToTheApi() throws Exception {
        FakeHttpClient transport = new FakeHttpClient().answer(200, PAGE);
        SharedCacheReader failing = key -> {
            throw new IllegalStateException("connection reset");
        };

        assertEquals("hacking", gateway(transport, failing).templates().getFirst().getKey());
    }

    @Test
    void anEmptyPageIsAnEmptyList() throws Exception {
        FakeHttpClient transport = new FakeHttpClient().answer(200,
                "{\"content\":[],\"totalSize\":0,\"totalPages\":0,\"empty\":true,\"size\":100,\"offset\":0,\"pageNumber\":0,\"numberOfElements\":0}");

        assertTrue(gateway(transport, miss()).templates().isEmpty(), "no templates yet");
    }

    @Test
    void aFailingApiSurfacesAsAnApiException() {
        FakeHttpClient transport = new FakeHttpClient().answer(500, "{}");

        assertThrows(ApiException.class, () -> gateway(transport, miss()).templates(), "the caller decides how to report it");
    }
}
