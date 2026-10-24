package net.onelitefeather.blackhole.velocity.clientrule;

import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.velocity.component.ClientRuleMessages;
import net.onelitefeather.blackhole.velocity.component.Permissions;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.function.Supplier;

/**
 * Tells the online staff of this proxy about a client rule hit. The proxy that saw the hit only
 * {@linkplain #announce announces} it on the {@link ClientRuleNoticeChannel}; the notice then comes back to
 * {@link #deliver} on every proxy, this one included, so each staff member is told exactly once wherever
 * they are connected. Only when nobody listens (Redis is down) the announcing proxy delivers it itself.
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
