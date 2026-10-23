package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.PunishTemplateResponse;
import net.onelitefeather.blackhole.velocity.auth.FakeHttpClient;
import net.onelitefeather.blackhole.velocity.auth.TokenSource;
import net.onelitefeather.blackhole.velocity.config.BlackholeApiClients;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.redis.RedisSyncService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Without the shared cache the gateway lists templates through the API, which answers with a page
 * object (the templates under {@code content}), not a bare array.
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

    /** A gateway whose Redis was never connected, so every cache read is a miss. */
    private static HttpBackendGateway gateway(FakeHttpClient transport) {
        ApiClient client = BlackholeApiClients.create("http://backend.test", TOKEN, transport);
        return new HttpBackendGateway(client, new RedisSyncService(new BlackholeConfig()), Clock.systemUTC());
    }

    @Test
    void aPagedTemplateListIsParsedIntoTheTemplates() throws Exception {
        FakeHttpClient transport = new FakeHttpClient().answer(200, PAGE);

        List<PunishTemplateResponse> templates = gateway(transport).templates();

        assertEquals(1, templates.size(), "the one template on the page");
        assertEquals("hacking", templates.getFirst().getKey());
        assertEquals(100, templates.getFirst().getSteps().getFirst().getEloCost());
    }

    @Test
    void anEmptyPageIsAnEmptyList() throws Exception {
        FakeHttpClient transport = new FakeHttpClient().answer(200,
                "{\"content\":[],\"totalSize\":0,\"totalPages\":0,\"empty\":true,\"size\":100,\"offset\":0,\"pageNumber\":0,\"numberOfElements\":0}");

        assertTrue(gateway(transport).templates().isEmpty(), "no templates yet");
    }

    @Test
    void aFailingApiSurfacesAsAnApiException() {
        FakeHttpClient transport = new FakeHttpClient().answer(500, "{}");

        assertThrows(ApiException.class, () -> gateway(transport).templates(), "the caller decides how to report it");
    }
}
