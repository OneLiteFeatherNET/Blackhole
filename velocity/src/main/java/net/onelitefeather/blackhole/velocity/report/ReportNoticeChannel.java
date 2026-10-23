package net.onelitefeather.blackhole.velocity.report;

import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * The way a new-report notice reaches the staff of every proxy. Every proxy registers a listener; a notice
 * that is published reaches each listener once, the publisher's own included.
 */
public interface ReportNoticeChannel {

    /** Registers the listener that delivers notices to this proxy's staff; call before connecting. */
    void onNotice(@NotNull Consumer<ReportNotice> listener);

    /**
     * Publishes the notice to every proxy.
     *
     * @return whether anybody was listening; false means the notice reached nobody, not even this proxy
     */
    boolean publish(@NotNull ReportNotice notice);
}
