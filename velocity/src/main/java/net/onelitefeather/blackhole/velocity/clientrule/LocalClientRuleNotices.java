package net.onelitefeather.blackhole.velocity.clientrule;

import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * The notice channel without a bus: nothing reaches the other proxies, so {@link ClientRuleNotifier}
 * delivers every hit to the staff of the proxy that saw it.
 */
public final class LocalClientRuleNotices implements ClientRuleNoticeChannel {

    @Override
    public void onClientRuleNotice(@NotNull Consumer<ClientRuleNotice> listener) {
        // No channel to listen on: every notice is delivered by the announcing proxy itself.
    }

    @Override
    public boolean publish(@NotNull ClientRuleNotice notice) {
        return false;
    }
}
