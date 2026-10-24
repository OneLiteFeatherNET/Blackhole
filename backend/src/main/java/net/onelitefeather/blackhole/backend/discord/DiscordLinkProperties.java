package net.onelitefeather.blackhole.backend.discord;

import io.micronaut.context.annotation.ConfigurationInject;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.NonNull;

import java.time.Duration;

/**
 * Discord account linking ({@code blackhole.discord.link}).
 *
 * @param ttl how long an issued link code stays valid
 */
@ConfigurationProperties("blackhole.discord.link")
public record DiscordLinkProperties(@NonNull Duration ttl) {

    @ConfigurationInject
    public DiscordLinkProperties {
    }
}
