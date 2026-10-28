package net.onelitefeather.blackhole.velocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.velocity.component.PunishmentScreens;
import net.onelitefeather.blackhole.velocity.enforcement.MuteCommandGate;
import net.onelitefeather.blackhole.velocity.enforcement.MuteNotices;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentState;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentSyncMessage;
import net.onelitefeather.blackhole.velocity.evidence.EvidenceCounts;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Optional;

/**
 * Keeps muted players from running the configured commands (private messages and emotes by default).
 * Only this class imports the event; the decision is in {@link MuteCommandGate}. The mute state is only
 * read for a covered command, so every other command costs nothing.
 */
public final class MuteCommandListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(MuteCommandListener.class);

    private final MuteCommandGate gate;
    private final PunishmentState state;
    private final EvidenceCounts evidenceCounts;
    private final MuteNotices notices;

    public MuteCommandListener(
            @NotNull MuteCommandGate gate, @NotNull PunishmentState state, @NotNull EvidenceCounts evidenceCounts, @NotNull MuteNotices notices
    ) {
        this.gate = gate;
        this.state = state;
        this.evidenceCounts = evidenceCounts;
        this.notices = notices;
    }

    @Subscribe
    public void onCommand(@NotNull CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player player) || !this.gate.covers(event.getCommand())) {
            return;
        }
        String owner = UUIDConverter.convertToSHA(player.getUniqueId());
        Optional<PunishmentSyncMessage> mute;
        try {
            mute = this.state.activeMute(owner);
        } catch (PunishmentState.Unavailable e) {
            // Same rule as chat: an unknown mute state never blocks the player.
            LOGGER.error("Could not check the mute state of {}, running the command: {}", player.getUsername(), e.getMessage());
            return;
        }
        if (mute.isEmpty()) {
            return;
        }
        CommandExecuteEvent.InvocationInfo info = event.getInvocationInfo();
        // Without invocation info the command is treated as unsigned: denied directly, never disconnecting.
        CommandExecuteEvent.SignedState signed = info == null ? CommandExecuteEvent.SignedState.UNSUPPORTED : info.signedState();
        MuteCommandGate.Verdict verdict = this.gate.verdict(event.getCommand(), signed, true);
        if (verdict == MuteCommandGate.Verdict.ALLOW) {
            return;
        }
        event.setResult(CommandExecuteEvent.CommandResult.denied());
        long evidence = this.evidenceCounts.of(mute.get().punishmentIdentifier()).orElse(0);
        Locale locale = PunishmentScreens.localeOf(player);
        if (verdict == MuteCommandGate.Verdict.DISCONNECT) {
            LOGGER.info("Disconnected muted player {} for a signed command: SignedVelocity is not installed on this proxy", player.getUsername());
            player.disconnect(this.notices.disconnectScreen(mute.get(), locale, evidence));
            return;
        }
        player.sendMessage(this.notices.notice(owner, mute.get(), locale, evidence));
    }
}
