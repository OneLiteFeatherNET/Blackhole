package net.onelitefeather.blackhole.velocity.report;

import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.velocity.component.Permissions;
import net.onelitefeather.blackhole.velocity.component.ReportMessages;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.function.Supplier;

/**
 * Tells the online staff of this proxy about a new report. The proxy that took the report only
 * {@linkplain #announce announces} it on the {@link ReportNoticeChannel}; the notice then comes back to
 * {@link #deliver} on every proxy, this one included, so each staff member is told exactly once wherever
 * they are connected. Only when nobody listens (Redis is down) the announcing proxy delivers it itself.
 */
public final class ReportNotifier {

    private final Supplier<Collection<? extends Player>> players;
    private final ReportNoticeChannel channel;
    private final Supplier<String> panelUrl;

    /**
     * @param players  the players connected to this proxy
     * @param panelUrl the admin panel's address, null to show no panel button
     */
    public ReportNotifier(
            @NotNull Supplier<Collection<? extends Player>> players, @NotNull ReportNoticeChannel channel,
            @NotNull Supplier<String> panelUrl
    ) {
        this.players = players;
        this.channel = channel;
        this.panelUrl = panelUrl;
        channel.onNotice(this::deliver);
    }

    /** Hands a new report to every proxy's staff. */
    public void announce(@NotNull ReportNotice notice) {
        if (!this.channel.publish(notice)) {
            deliver(notice);
        }
    }

    /** Tells this proxy's online staff who may be notified. */
    public void deliver(@NotNull ReportNotice notice) {
        String panel = this.panelUrl.get();
        for (Player player : this.players.get()) {
            if (player.hasPermission(Permissions.REPORT_NOTIFY)) {
                player.sendMessage(ReportMessages.notice(notice, panel));
            }
        }
    }
}
