package net.onelitefeather.blackhole.velocity.listener;

import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import net.kyori.adventure.text.renderer.ComponentRenderer;
import net.onelitefeather.blackhole.client.api.IntelApi;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.model.PunishEntryDTO;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.velocity.TestData;
import net.onelitefeather.blackhole.velocity.auth.FakeHttpClient;
import net.onelitefeather.blackhole.velocity.auth.TokenSource;
import net.onelitefeather.blackhole.velocity.component.PunishmentScreens;
import net.onelitefeather.blackhole.velocity.config.BlackholeApiClients;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.enforcement.BanIds;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentMirror;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentState;
import net.onelitefeather.blackhole.velocity.evidence.MutableClock;
import net.onelitefeather.blackhole.velocity.intel.HttpSessionBackend;
import net.onelitefeather.blackhole.velocity.intel.SessionPlayer;
import net.onelitefeather.blackhole.velocity.intel.SessionReporter;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The login decision and its session report, with the backend answered by a fake transport: nothing
 * here touches the network, and the report runs on the calling thread.
 */
class PlayerLoginListenerTest {

    private static final UUID ALICE_ID = SessionPlayer.ALICE_ID;
    private static final String ALICE = UUIDConverter.convertToSHA(ALICE_ID);
    private static final int PROTOCOL = ProtocolVersion.MINECRAFT_1_21.getProtocol();
    private static final Executor RUN_AT_ONCE = Runnable::run;
    private static final Executor QUEUE_FULL = task -> {
        throw new RejectedExecutionException("queue full");
    };
    private static final TokenSource TOKEN = new TokenSource() {
        @Override
        public String token() {
            return "proxy-token";
        }

        @Override
        public void rejected(String token) {
        }
    };

    private final MutableClock clock = new MutableClock();
    private final PunishmentMirror mirror = new PunishmentMirror(clock);
    private final AtomicLong sequence = new AtomicLong();
    private final FakeHttpClient transport = new FakeHttpClient().answer(200, "{}");
    private final ApiClient client = BlackholeApiClients.create("http://backend.test", TOKEN, transport);

    private PunishmentScreens screens() {
        return new PunishmentScreens(clock, new BlackholeConfig(), TestData.standardRegistry(),
                (ComponentRenderer<Locale>) (component, locale) -> component, BanIds.NONE);
    }

    private PlayerLoginListener listener(PunishmentState state, Executor executor) {
        SessionReporter sessions = new SessionReporter(new HttpSessionBackend(new IntelApi(client)), executor,
                () -> new UUID(0, sequence.incrementAndGet()));
        return new PlayerLoginListener(client, state, screens(), identifier -> OptionalLong.empty(), sessions);
    }

    private PunishmentState stateWithProfile(Optional<PunishProfileDTO> profile) {
        return new PunishmentState(TestData.standardRegistry(), mirror, owner -> profile, clock, () -> true, Duration.ofMinutes(1));
    }

    private static Optional<PunishProfileDTO> permanentBanOfAlice() {
        PunishEntryDTO ban = new PunishEntryDTO().identifier("p1").permanent(true).lifted(false)
                .metaData(new HashMap<>(Map.of("reason", "Hacking")));
        return Optional.of(new PunishProfileDTO().owner(ALICE).active(Map.of("network", ban)));
    }

    /** The bodies of the requests to the session routes, in the order they were sent. */
    private List<String> intelBodies() {
        return bodiesWhere(request -> request.uri().getPath().startsWith("/intel/"));
    }

    /** The bodies of the requests that are not session routes: the profile update. */
    private List<String> profileBodies() {
        return bodiesWhere(request -> !request.uri().getPath().startsWith("/intel/"));
    }

    private List<String> bodiesWhere(java.util.function.Predicate<HttpRequest> match) {
        List<String> bodies = new java.util.ArrayList<>();
        List<HttpRequest> requests = transport.requests();
        for (int i = 0; i < requests.size(); i++) {
            if (match.test(requests.get(i))) {
                bodies.add(transport.bodyOf(i));
            }
        }
        return bodies;
    }

    @Test
    void aDeniedLoginIsDeniedAndItsStartIsReportedAsDenied() {
        PlayerLoginListener listener = listener(stateWithProfile(permanentBanOfAlice()), RUN_AT_ONCE);
        LoginEvent event = new LoginEvent(SessionPlayer.alice());

        listener.onLogin(event);

        assertFalse(event.getResult().isAllowed(), "the banned player is denied");
        assertEquals(1, intelBodies().size(), "one start report for the login");
        assertTrue(intelBodies().getFirst().contains("\"outcome\":\"DENIED\""), intelBodies().getFirst());
    }

    @Test
    void anAllowedLoginReportsTheStartWithTheProtocolVersionAndUpdatesTheProfileWithIt() {
        PlayerLoginListener listener = listener(stateWithProfile(Optional.empty()), RUN_AT_ONCE);
        LoginEvent event = new LoginEvent(SessionPlayer.alice());

        listener.onLogin(event);

        assertTrue(event.getResult().isAllowed(), "an allowed login stays allowed");
        assertEquals(1, intelBodies().size(), "one start report for the login");
        String start = intelBodies().getFirst();
        assertTrue(start.contains("\"outcome\":\"ALLOWED\""), start);
        assertTrue(start.contains("\"protocolVersion\":" + PROTOCOL), start);
        assertEquals(1, profileBodies().size(), "the profile session info is still updated once");
        assertTrue(profileBodies().getFirst().contains("\"protocolVersion\":" + PROTOCOL), profileBodies().getFirst());
    }

    @Test
    void aBackendOutageWhileCheckingTheBansAllowsTheLoginAndReportsItAsAllowed() {
        PunishmentState state = new PunishmentState(TestData.standardRegistry(), mirror, owner -> {
            throw new IllegalStateException("backend down");
        }, clock, () -> false, Duration.ofMinutes(1));
        PlayerLoginListener listener = listener(state, RUN_AT_ONCE);
        LoginEvent event = new LoginEvent(SessionPlayer.alice());

        listener.onLogin(event);

        assertTrue(event.getResult().isAllowed(), "the outage never denies the login");
        assertEquals(1, intelBodies().size(), "the login is still reported");
        assertTrue(intelBodies().getFirst().contains("\"outcome\":\"ALLOWED\""), intelBodies().getFirst());
    }

    @Test
    void aSessionBackendThatRefusesTheReportDoesNotChangeTheLogin() {
        transport.answer(503, "");
        PlayerLoginListener listener = listener(stateWithProfile(Optional.empty()), RUN_AT_ONCE);
        LoginEvent event = new LoginEvent(SessionPlayer.alice());

        listener.onLogin(event);

        assertTrue(event.getResult().isAllowed(), "a refused report never reaches the login");
    }

    @Test
    void aFullReportQueueDoesNotChangeTheLogin() {
        PlayerLoginListener listener = listener(stateWithProfile(Optional.empty()), QUEUE_FULL);
        LoginEvent event = new LoginEvent(SessionPlayer.alice());

        listener.onLogin(event);

        assertTrue(event.getResult().isAllowed(), "a dropped report never reaches the login");
        assertTrue(intelBodies().isEmpty(), "the dropped report is not sent");
    }
}
