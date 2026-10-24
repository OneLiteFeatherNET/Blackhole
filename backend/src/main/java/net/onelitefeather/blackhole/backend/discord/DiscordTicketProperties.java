package net.onelitefeather.blackhole.backend.discord;

import io.micronaut.context.annotation.ConfigurationInject;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;

/**
 * Limits of the Discord ticket facade ({@code blackhole.discord.ticket}). They bound what one request may carry or return; they are not
 * rate limits (infra owns those).
 *
 * @param outboxPageSize    how many events one outbox read returns when the bot names no limit
 * @param outboxMaxPageSize the most it may ask for
 * @param listLimit         the most tickets a listing returns
 * @param maxBatchItems     the most messages (and deletes) one ingest request may carry
 * @param maxTextLength     a longer message text is cut to this many characters (Discord's own limit with Nitro is 4000)
 * @param maxLeakCandidates the most candidates one leak check looks at
 */
@ConfigurationProperties("blackhole.discord.ticket")
public record DiscordTicketProperties(
        @Bindable(defaultValue = "50") int outboxPageSize,
        @Bindable(defaultValue = "200") int outboxMaxPageSize,
        @Bindable(defaultValue = "200") int listLimit,
        @Bindable(defaultValue = "200") int maxBatchItems,
        @Bindable(defaultValue = "4000") int maxTextLength,
        @Bindable(defaultValue = "5") int maxLeakCandidates
) {

    @ConfigurationInject
    public DiscordTicketProperties {
    }

    /** The defaults, for tests that build a service by hand. */
    public static DiscordTicketProperties defaults() {
        return new DiscordTicketProperties(50, 200, 200, 200, 4000, 5);
    }
}
