package net.onelitefeather.blackhole.backend.events;

import io.micronaut.context.annotation.ConfigurationInject;
import io.micronaut.context.annotation.ConfigurationProperties;

import java.time.Duration;

/**
 * Settings of the NATS JetStream event bus, bound from {@code blackhole.events.nats.*} (every value
 * and its meaning is documented in {@code application.yml}).
 *
 * @param url             server URL(s), comma separated; {@code nats://} or {@code tls://}
 * @param credsFile       path of a {@code .creds} file; empty means no authentication
 * @param connectionName  the name this client shows on the server
 * @param connectTimeout  how long one connection attempt may take
 * @param streamReplicas  replicas of the stream (1 locally, 3 in a cluster)
 * @param streamMaxAge    how long a message is kept
 * @param streamMaxBytes  upper bound of the stream's size
 */
@ConfigurationProperties("blackhole.events.nats")
public record NatsSettings(
        String url,
        String credsFile,
        String connectionName,
        Duration connectTimeout,
        int streamReplicas,
        Duration streamMaxAge,
        long streamMaxBytes
) {

    @ConfigurationInject
    public NatsSettings {
    }
}
