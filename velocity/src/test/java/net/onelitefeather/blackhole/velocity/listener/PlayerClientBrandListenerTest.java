package net.onelitefeather.blackhole.velocity.listener;

import com.velocitypowered.api.event.player.PlayerClientBrandEvent;
import net.onelitefeather.blackhole.client.api.IntelApi;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.model.SessionOutcome;
import net.onelitefeather.blackhole.velocity.auth.FakeHttpClient;
import net.onelitefeather.blackhole.velocity.auth.TokenSource;
import net.onelitefeather.blackhole.velocity.config.BlackholeApiClients;
import net.onelitefeather.blackhole.velocity.intel.HttpSessionBackend;
import net.onelitefeather.blackhole.velocity.intel.SessionPlayer;
import net.onelitefeather.blackhole.velocity.intel.SessionReporter;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The client brand reaches the session as a PATCH and the profile as before; the backend is a fake transport. */
class PlayerClientBrandListenerTest {

    private static final TokenSource TOKEN = new TokenSource() {
        @Override
        public String token() {
            return "proxy-token";
        }

        @Override
        public void rejected(String token) {
        }
    };

    @Test
    void theBrandIsPatchedOntoTheSessionOfTheConnection() {
        FakeHttpClient transport = new FakeHttpClient().answer(200, "{}");
        ApiClient client = BlackholeApiClients.create("http://backend.test", TOKEN, transport);
        AtomicLong sequence = new AtomicLong();
        SessionReporter sessions = new SessionReporter(new HttpSessionBackend(new IntelApi(client)), Runnable::run,
                () -> new UUID(0, sequence.incrementAndGet()));
        var alice = SessionPlayer.alice();
        sessions.reportStart(alice, SessionOutcome.ALLOWED);

        new PlayerClientBrandListener(client, sessions).onClientBrand(new PlayerClientBrandEvent(alice, "vanilla"));

        HttpRequest patch = transport.requests().get(1);
        assertEquals("PATCH", patch.method(), "the brand is an update of the session");
        assertEquals("/intel/sessions/00000000-0000-0000-0000-000000000001", patch.uri().getPath(),
                "the session of this connection, the one the start created");
        assertTrue(transport.bodyOf(1).contains("\"clientBrand\":\"vanilla\""), transport.bodyOf(1));
    }

    @Test
    void theBrandIsStillSentAsProfileSessionInfo() {
        FakeHttpClient transport = new FakeHttpClient().answer(200, "{}");
        ApiClient client = BlackholeApiClients.create("http://backend.test", TOKEN, transport);
        SessionReporter sessions = new SessionReporter(new HttpSessionBackend(new IntelApi(client)), Runnable::run,
                () -> UUID.randomUUID());

        new PlayerClientBrandListener(client, sessions).onClientBrand(new PlayerClientBrandEvent(SessionPlayer.alice(), "vanilla"));

        long profileBrands = transport.requests().stream()
                .filter(request -> !request.uri().getPath().startsWith("/intel/"))
                .count();
        assertEquals(1, profileBrands, "the profile update is unchanged and still sent");
    }
}
