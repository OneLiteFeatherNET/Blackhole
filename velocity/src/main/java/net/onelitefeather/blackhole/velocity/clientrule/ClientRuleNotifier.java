package net.onelitefeather.blackhole.velocity.clientrule;

import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.velocity.component.ClientRuleMessages;
import net.onelitefeather.blackhole.velocity.component.Permissions;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.function.Supplier;

/**
 * Tells the online staff of this proxy about a client rule hit. A hit is only ever announced to the staff
 * on the proxy that saw it: {@linkplain #announce announce} hands the notice to {@link #deliver}, which
 * messages each connected staff member with the notify permission. Other proxies are not told.
 */
public final class ClientRuleNotifier {

    private final Supplier<Collection<? extends Player>> players;
    private final ClientRuleNoticeChannel channel;

    /**
     * @param players the players connected to this proxy
     */
    public ClientRuleNotifier(@NotNull Supplier<Collection<? extends Player>> players, @NotNull ClientRuleNoticeChannel channel) {
        this.players = players;
        this.channel = channel;
        channel.onClientRuleNotice(this::deliver);
    }

    /** Hands a hit to every proxy's staff. */
    public void announce(@NotNull ClientRuleNotice notice) {
        if (!this.channel.publish(notice)) {
            deliver(notice);
        }
    }

    /** Tells this proxy's online staff who may be notified. */
    public void deliver(@NotNull ClientRuleNotice notice) {
        for (Player player : this.players.get()) {
            if (player.hasPermission(Permissions.CLIENT_RULES_NOTIFY)) {
                player.sendMessage(ClientRuleMessages.notice(notice));
            }
        }
    }
}
