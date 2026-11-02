package net.onelitefeather.blackhole.velocity.listener;

import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.command.CommandExecuteEvent.InvocationInfo;
import com.velocitypowered.api.event.command.CommandExecuteEvent.SignedState;
import com.velocitypowered.api.event.command.CommandExecuteEvent.Source;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.renderer.ComponentRenderer;
import net.onelitefeather.blackhole.velocity.TestData;
import net.onelitefeather.blackhole.velocity.component.PunishmentScreens;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.enforcement.BanIds;
import net.onelitefeather.blackhole.velocity.enforcement.MuteCommandGate;
import net.onelitefeather.blackhole.velocity.enforcement.MuteNoticeCooldown;
import net.onelitefeather.blackhole.velocity.enforcement.MuteNotices;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentMirror;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentState;
import net.onelitefeather.blackhole.velocity.evidence.MutableClock;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covered commands of players whose mute state is unknown, with the backend stubbed: nothing touches the network. */
class MuteCommandListenerTest {

    private static final UUID ALICE_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final String LINE = "msg bob hi";

    private final MutableClock clock = new MutableClock();
    private final PunishmentMirror mirror = new PunishmentMirror(clock);
    private final PunishmentScreens screens = new PunishmentScreens(clock, new BlackholeConfig(), TestData.standardRegistry(),
            (ComponentRenderer<Locale>) (component, locale) -> component, BanIds.NONE);
    private final MuteNotices notices = new MuteNotices(screens, new MuteNoticeCooldown(clock, Duration.ofSeconds(30)));

    private static final class RecordingPlayer {
        final List<Component> messages = new ArrayList<>();
        final List<Component> disconnects = new ArrayList<>();
        final Player player;

        RecordingPlayer() {
            this.player = (Player) Proxy.newProxyInstance(RecordingPlayer.class.getClassLoader(), new Class<?>[]{Player.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> ALICE_ID;
                        case "getUsername" -> "Alice";
                        case "sendMessage" -> {
                            messages.add((Component) args[0]);
                            yield null;
                        }
                        case "disconnect" -> {
                            disconnects.add((Component) args[0]);
                            yield null;
                        }
                        case "hashCode" -> ALICE_ID.hashCode();
                        case "equals" -> proxy == args[0];
                        case "toString" -> "RecordingPlayer";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private MuteCommandListener listener(PunishmentState.ProfileReader reader, boolean relayPresent) {
        PunishmentState state = new PunishmentState(TestData.standardRegistry(), mirror, reader, clock, () -> true, Duration.ofMinutes(1));
        return new MuteCommandListener(new MuteCommandGate(List.of("msg"), relayPresent), state,
                punishmentIdentifier -> OptionalLong.empty(), notices);
    }

    private static PunishmentState.ProfileReader backendDown() {
        return owner -> {
            throw new IOException("backend down");
        };
    }

    private static CommandExecuteEvent command(Player player, SignedState signed) {
        return new CommandExecuteEvent(player, LINE, new InvocationInfo(signed, Source.PLAYER));
    }

    @Test
    void anUnknownMuteStateWithTheBackendDownDeniesAnUnsignedCommand() {
        RecordingPlayer alice = new RecordingPlayer();
        CommandExecuteEvent event = command(alice.player, SignedState.UNSIGNED);

        listener(backendDown(), false).onCommand(event);

        assertFalse(event.getResult().isAllowed(), "a covered command whose mute state is unknown must not run");
    }

    @Test
    void anUnknownMuteStateWithTheBackendDownTellsThePlayerChatIsUnavailable() {
        RecordingPlayer alice = new RecordingPlayer();

        listener(backendDown(), false).onCommand(command(alice.player, SignedState.UNSIGNED));

        assertEquals(List.of(notices.unavailable()), alice.messages, "the player gets the neutral unavailable notice, not the mute screen");
        assertTrue(alice.disconnects.isEmpty(), "an unknown state never disconnects the sender");
    }

    @Test
    void anUnknownMuteStateWithTheBackendSayingNotMutedRunsTheCommand() {
        RecordingPlayer alice = new RecordingPlayer();
        CommandExecuteEvent event = command(alice.player, SignedState.UNSIGNED);

        listener(owner -> Optional.empty(), false).onCommand(event);

        assertTrue(event.getResult().isAllowed(), "the backend answered that the player is not muted, so the command runs");
        assertTrue(alice.messages.isEmpty(), "no notice is sent to an unmuted player");
    }

    @Test
    void aSignedCommandWithAnUnknownMuteStateRunsWithoutSignedVelocityRatherThanDisconnecting() {
        RecordingPlayer alice = new RecordingPlayer();
        CommandExecuteEvent event = command(alice.player, SignedState.SIGNED_WITH_ARGS);

        listener(backendDown(), false).onCommand(event);

        assertTrue(event.getResult().isAllowed(), "denying a signed command would make Velocity kick the sender");
        assertTrue(alice.disconnects.isEmpty(), "the sender is not disconnected on an unknown state");
    }
}
