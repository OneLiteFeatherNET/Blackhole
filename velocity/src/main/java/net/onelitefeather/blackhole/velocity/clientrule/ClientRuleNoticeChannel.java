package net.onelitefeather.blackhole.velocity.clientrule;

import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * The way a client rule notice reaches the staff of every proxy. Every proxy registers a listener; a
 * notice that is published reaches each listener once, the publisher's own included.
 */
public interface ClientRuleNoticeChannel {

    /** Registers the listener that delivers notices to this proxy's staff; call before connecting. */
    void onClientRuleNotice(@NotNull Consumer<ClientRuleNotice> listener);

    /**
     * Publishes the notice to every proxy.
     *
     * @return whether anybody was listening; false means the notice reached nobody, not even this proxy
     */
    boolean publish(@NotNull ClientRuleNotice notice);
}
