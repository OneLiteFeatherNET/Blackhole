package net.onelitefeather.blackhole.velocity.evidence;

import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.translation.GlobalTranslator;
import net.onelitefeather.blackhole.velocity.TestData;
import net.onelitefeather.blackhole.velocity.component.PunishmentScreens;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.config.ChatCaptureConfig;
import net.onelitefeather.blackhole.velocity.enforcement.MuteNoticeCooldown;
import net.onelitefeather.blackhole.velocity.enforcement.MuteNotices;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentMirror;
import net.onelitefeather.blackhole.velocity.listener.ChatCaptureListener;
import net.onelitefeather.blackhole.velocity.listener.PlayerDisconnectListener;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatListenersTest {

    private static final UUID ALICE_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final String ALICE = UUIDConverter.convertToSHA(ALICE_ID);

    private final MutableClock clock = new MutableClock();
    private final Player alice = FakePlayer.on(ALICE_ID, "lobby");

    private ChatBuffer buffer(boolean enabled) {
        ChatCaptureConfig config = new ChatCaptureConfig();
        config.setEnabled(enabled);
        return new ChatBuffer(clock, config);
    }

    private static List<String> texts(ChatBuffer buffer) {
        return buffer.linesOf(ALICE).stream().map(ChatBuffer.Line::text).toList();
    }

    @Test
    void aPublicChatMessageIsBufferedUnderTheOwnerHash() {
        ChatBuffer buffer = buffer(true);

        new ChatCaptureListener(buffer).onChat(new PlayerChatEvent(alice, "hello there"));

        assertEquals(List.of("hello there"), texts(buffer), "the message must be buffered under the hash, not the UUID");
        assertEquals("lobby", buffer.linesOf(ALICE).getFirst().server(), "the line remembers the server it was sent on");
    }

    @Test
    void aMessageThatStartsWithASlashIsNotBuffered() {
        ChatBuffer buffer = buffer(true);

        new ChatCaptureListener(buffer).onChat(new PlayerChatEvent(alice, "/ban someone hacking"));

        assertTrue(texts(buffer).isEmpty(), "commands must never be captured");
    }

    @Test
    void aDeniedMessageIsNotBuffered() {
        ChatBuffer buffer = buffer(true);
        PlayerChatEvent event = new PlayerChatEvent(alice, "muted words");
        event.setResult(PlayerChatEvent.ChatResult.denied());

        new ChatCaptureListener(buffer).onChat(event);

        assertTrue(texts(buffer).isEmpty(), "a message that was not delivered is not public chat");
    }

    @Test
    void theMessageAsRewrittenByAnotherPluginIsBuffered() {
        ChatBuffer buffer = buffer(true);
        PlayerChatEvent event = new PlayerChatEvent(alice, "original");
        event.setResult(PlayerChatEvent.ChatResult.message("filtered"));

        new ChatCaptureListener(buffer).onChat(event);

        assertEquals(List.of("filtered"), texts(buffer), "the evidence shows what the other players saw");
    }

    @Test
    void nothingIsBufferedWhileCaptureIsDisabled() {
        ChatBuffer buffer = buffer(false);

        new ChatCaptureListener(buffer).onChat(new PlayerChatEvent(alice, "hello"));

        assertTrue(texts(buffer).isEmpty(), "the default configuration must not store chat");
        assertEquals(0, buffer.trackedPlayers());
    }

    @Test
    void aMessageFromAPlayerOnNoServerIsNotBuffered() {
        ChatBuffer buffer = buffer(true);

        new ChatCaptureListener(buffer).onChat(new PlayerChatEvent(FakePlayer.offServer(ALICE_ID), "hello"));

        assertTrue(texts(buffer).isEmpty(), "a line without a server cannot be placed in a conversation");
    }

    @Test
    void disconnectingClearsTheBufferedChat() {
        ChatBuffer buffer = buffer(true);
        new ChatCaptureListener(buffer).onChat(new PlayerChatEvent(alice, "hello"));

        MuteNotices notices = new MuteNotices(
                new PunishmentScreens(clock, new BlackholeConfig(), TestData.standardRegistry(), GlobalTranslator.renderer()),
                new MuteNoticeCooldown(clock, Duration.ofSeconds(30)));
        new PlayerDisconnectListener(new PunishmentMirror(), buffer, notices)
                .onDisconnect(new DisconnectEvent(alice, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));

        assertTrue(texts(buffer).isEmpty(), "the buffer must not outlive the session");
        assertEquals(0, buffer.trackedPlayers());
    }
}
