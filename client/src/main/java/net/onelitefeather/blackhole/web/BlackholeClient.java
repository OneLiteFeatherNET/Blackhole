package net.onelitefeather.blackhole.web;

import net.onelitefeather.blackhole.request.entry.PunishWebRequests;
import net.onelitefeather.blackhole.request.profile.ProfileWebRequests;
import net.onelitefeather.blackhole.request.template.TemplateWebRequests;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;

/**
 * The interface is the entry point to access different web requests implemented in the client.
 * The client is responsible for the communication with the server.
 *
 * @author theEvilReaper
 * @version 1.0.0
 * @since 1.0.0
 */
public sealed interface BlackholeClient permits BlackholeWebClient {

    /**
     * Create a new BlackholeClient with the given URL.
     *
     * @param url the URL of the server
     * @return the new BlackholeClient instance
     */
    @Contract(value = "_ -> new", pure = true)
    static @NotNull BlackholeClient newClient(@NotNull String url) {
        return new BlackholeWebClient(url, Duration.ofSeconds(10));
    }

    /**
     * Create a new BlackholeClient with the given URL and timeout.
     *
     * @param url     the URL of the server
     * @param timeout the timeout for the client
     * @return the new BlackholeClient instance
     */
    @Contract(value = "_, _ -> new", pure = true)
    static @NotNull BlackholeClient newClient(@NotNull String url, @NotNull Duration timeout) {
        return new BlackholeWebClient(url, timeout);
    }

    /**
     * Get the template requests for the client.
     *
     * @return the template requests
     */
    @NotNull TemplateWebRequests templateRequests();

    /**
     * Get the profile requests for the client.
     *
     * @return the profile requests
     */
    @NotNull ProfileWebRequests profileRequests();

    /**
     * Get the punish requests for the client.
     *
     * @return the punish requests
     */
    @NotNull PunishWebRequests punishRequests();
}
