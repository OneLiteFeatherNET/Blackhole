package net.onelitefeather.blackhole.velocity.listener;

import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.renderer.ComponentRenderer;
import net.onelitefeather.blackhole.client.api.EloApi;
import net.onelitefeather.blackhole.client.model.EloChatSignalRequest;
import net.onelitefeather.blackhole.client.model.EloChatSignalResponse;
import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.velocity.TestData;
import net.onelitefeather.blackhole.velocity.component.PunishmentScreens;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.enforcement.BanIds;
import net.onelitefeather.blackhole.velocity.enforcement.MuteChatDenial;
import net.onelitefeather.blackhole.velocity.enforcement.MuteNoticeCooldown;
import net.onelitefeather.blackhole.velocity.enforcement.MuteNotices;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentMirror;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentState;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentSyncMessage;
import net.onelitefeather.blackhole.velocity.evidence.MutableClock;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Chat of muted and unmuted players, with the backend stubbed: nothing here touches the network. */
class PlayerChatListenerTest {

    private static final UUID ALICE_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final String ALICE = UUIDConverter.convertToSHA(ALICE_ID);
    private static final String MESSAGE = "hello there";

    private final MutableClock clock = new MutableClock();
    private final PunishmentMirror mirror = new PunishmentMirror(clock);
    private final PunishmentState state = new PunishmentState(TestData.standardRegistry(), mirror, owner -> Optional.empty(), clock,
            () -> true, Duration.ofMinutes(1));
    private final PunishmentScreens screens = new PunishmentScreens(clock, new BlackholeConfig(), TestData.standardRegistry(),
            (ComponentRenderer<Locale>) (component, locale) -> component, BanIds.NONE);
    private final MuteNotices notices = new MuteNotices(screens, new MuteNoticeCooldown(clock, Duration.ofSeconds(30)));
    private final List<String> scored = new ArrayList<>();
    private final EloApi elo = new EloApi(new net.onelitefeather.blackhole.client.invoker.ApiClient()) {
        @Override
        public EloChatSignalResponse submitChatSignal(EloChatSignalRequest request) {
            scored.add(request.getMessage());
            return new EloChatSignalResponse();
        }
    };

    private static final class RecordingPlayer {
        final UUID uuid = ALICE_ID;
        final ProtocolVersion version;
        final List<Component> messages = new ArrayList<>();
        final List<Component> disconnects = new ArrayList<>();
        final Player player;

        RecordingPlayer(ProtocolVersion version) {
            this.version = version;
            this.player = (Player) Proxy.newProxyInstance(RecordingPlayer.class.getClassLoader(), new Class<?>[]{Player.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> uuid;
                        case "getUsername" -> "Alice";
                        case "getProtocolVersion" -> version;
                        case "sendMessage" -> {
                            messages.add((Component) args[0]);
                            yield null;
                        }
                        case "disconnect" -> {
                            disconnects.add((Component) args[0]);
                            yield null;
                        }
                        case "hashCode" -> uuid.hashCode();
                        case "equals" -> proxy == args[0];
                        case "toString" -> "RecordingPlayer";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private void muted() {
        mirror.load(ALICE, List.of(new PunishmentSyncMessage(ALICE, "chat", EnforcementKind.MUTE.name(), PunishmentSyncMessage.STATE_SET,
                "p1", null, "spam", null, false)));
    }

    private PlayerChatListener listener(boolean relayPresent) {
        return new PlayerChatListener(elo, state, punishmentIdentifier -> OptionalLong.empty(), new MuteChatDenial(relayPresent), notices);
    }

    @Test
    void aSignedMessageOfAMutedPlayerWithoutSignedVelocityIsNotDelivered() {
        muted();
        RecordingPlayer alice = new RecordingPlayer(ProtocolVersion.MINECRAFT_1_21);
        PlayerChatEvent event = new PlayerChatEvent(alice.player, MESSAGE);

        listener(false).onChat(event);

        assertFalse(event.getResult().isAllowed(), "the signed message must not be delivered");
    }

    @Test
    void aSignedMessageOfAMutedPlayerWithoutSignedVelocityDisconnectsThePlayer() {
        muted();
        RecordingPlayer alice = new RecordingPlayer(ProtocolVersion.MINECRAFT_1_21);

        listener(false).onChat(new PlayerChatEvent(alice.player, MESSAGE));

        assertEquals(1, alice.disconnects.size(), "the player is disconnected at the chat attempt");
    }

    @Test
    void theDisconnectShowsTheMuteScreen() {
        muted();
        RecordingPlayer alice = new RecordingPlayer(ProtocolVersion.MINECRAFT_1_21);
        PunishmentSyncMessage mute = state.activeMute(ALICE).orElseThrow();

        listener(false).onChat(new PlayerChatEvent(alice.player, MESSAGE));

        assertEquals(screens.screen(mute, Locale.ENGLISH, PunishmentScreens.Surface.DISCONNECT, false, 0), alice.disconnects.getFirst(),
                "the reason is the mute screen, not a generic kick");
    }

    @Test
    void anUnsignedMessageOfAMutedPlayerWithoutSignedVelocityGetsTheNoticeWithoutADisconnect() {
        muted();
        RecordingPlayer alice = new RecordingPlayer(ProtocolVersion.MINECRAFT_1_19);
        PlayerChatEvent event = new PlayerChatEvent(alice.player, MESSAGE);

        listener(false).onChat(event);

        assertFalse(event.getResult().isAllowed(), "the unsigned message is denied");
        assertTrue(alice.disconnects.isEmpty(), "unsigned chat is never a reason to disconnect");
        assertEquals(1, alice.messages.size(), "the mute notice is sent");
    }

    @Test
    void aMessageOfAMutedPlayerWithSignedVelocityIsDeniedWithoutADisconnect() {
        muted();
        RecordingPlayer alice = new RecordingPlayer(ProtocolVersion.MINECRAFT_1_21);

        listener(true).onChat(new PlayerChatEvent(alice.player, MESSAGE));

        assertTrue(alice.disconnects.isEmpty(), "the relay carries the cancel, so the player stays connected");
    }

    @Test
    void aMessageOfAMutedPlayerIsNotScored() {
        muted();
        RecordingPlayer alice = new RecordingPlayer(ProtocolVersion.MINECRAFT_1_21);

        listener(false).onChat(new PlayerChatEvent(alice.player, MESSAGE));

        assertTrue(scored.isEmpty(), "a message that was not delivered is not scored");
    }

    @Test
    void aMessageOfAPlayerWithoutMuteIsDeliveredAndScored() {
        RecordingPlayer alice = new RecordingPlayer(ProtocolVersion.MINECRAFT_1_21);
        PlayerChatEvent event = new PlayerChatEvent(alice.player, MESSAGE);

        listener(false).onChat(event);

        assertTrue(event.getResult().isAllowed(), "an unmuted message is delivered");
        assertEquals(List.of(MESSAGE), scored, "and submitted for ELO scoring");
        assertTrue(alice.disconnects.isEmpty(), "nothing else happens to an unmuted player");
    }

    /** Holds the submitted tasks until the test runs them, so the test decides when a signal is sent. */
    private static final class ManualExecutor implements Executor {
        final Deque<Runnable> pending = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            this.pending.add(task);
        }

        void runAll() {
            while (!this.pending.isEmpty()) {
                this.pending.poll().run();
            }
        }
    }

    @Test
    void theDeliveryDecisionDoesNotRunTheChatSignalOnTheEventThread() {
        ManualExecutor signals = new ManualExecutor();
        RecordingPlayer alice = new RecordingPlayer(ProtocolVersion.MINECRAFT_1_21);
        PlayerChatEvent event = new PlayerChatEvent(alice.player, MESSAGE);
        PlayerChatListener listener = new PlayerChatListener(elo, state, punishmentIdentifier -> OptionalLong.empty(),
                new MuteChatDenial(false), notices, signals);

        listener.onChat(event);

        assertTrue(event.getResult().isAllowed(), "the decision is made without waiting for the signal");
        assertTrue(scored.isEmpty(), "the signal is queued, not sent on the event thread");
        signals.runAll();
        assertEquals(List.of(MESSAGE), scored, "the queued signal is still submitted");
    }

    @Test
    void aBlockedChatSignalDoesNotHoldTheDeliveryDecision() {
        CountDownLatch release = new CountDownLatch(1);
        EloApi blocked = new EloApi(new net.onelitefeather.blackhole.client.invoker.ApiClient()) {
            @Override
            public EloChatSignalResponse submitChatSignal(EloChatSignalRequest request) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new EloChatSignalResponse();
            }
        };
        ExecutorService signals = Executors.newSingleThreadExecutor();
        try {
            PlayerChatListener listener = new PlayerChatListener(blocked, state, punishmentIdentifier -> OptionalLong.empty(),
                    new MuteChatDenial(false), notices, signals);
            RecordingPlayer alice = new RecordingPlayer(ProtocolVersion.MINECRAFT_1_21);
            PlayerChatEvent event = new PlayerChatEvent(alice.player, MESSAGE);

            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> listener.onChat(event),
                    "the chat must not wait for a blocked chat signal");

            assertTrue(event.getResult().isAllowed(), "the message is delivered while the signal is still blocked");
        } finally {
            release.countDown();
            signals.shutdownNow();
        }
    }

    @Test
    void aChatSignalThatCannotBeQueuedIsDroppedAndCounted() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        EloApi blocked = new EloApi(new net.onelitefeather.blackhole.client.invoker.ApiClient()) {
            @Override
            public EloChatSignalResponse submitChatSignal(EloChatSignalRequest request) {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new EloChatSignalResponse();
            }
        };
        ExecutorService signals = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.AbortPolicy());
        try {
            PlayerChatListener listener = new PlayerChatListener(blocked, state, punishmentIdentifier -> OptionalLong.empty(),
                    new MuteChatDenial(false), notices, signals);
            RecordingPlayer alice = new RecordingPlayer(ProtocolVersion.MINECRAFT_1_21);
            listener.onChat(new PlayerChatEvent(alice.player, "first"));
            assertTrue(started.await(5, TimeUnit.SECONDS), "the first signal occupies the only thread");
            listener.onChat(new PlayerChatEvent(alice.player, "second"));
            PlayerChatEvent third = new PlayerChatEvent(alice.player, "third");

            listener.onChat(third);

            assertEquals(1L, listener.droppedSignals(), "the signal that does not fit the queue is dropped and counted");
            assertTrue(third.getResult().isAllowed(), "a dropped signal never changes the delivery");
        } finally {
            release.countDown();
            signals.shutdownNow();
        }
    }
}
