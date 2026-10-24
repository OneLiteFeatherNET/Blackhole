package net.onelitefeather.blackhole.backend.publicapi;

import io.micronaut.context.annotation.ConfigurationInject;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.NonNull;

import java.time.Duration;

/**
 * Player session limits ({@code blackhole.public.session}).
 *
 * @param idle          a session ends after this long without a request (sliding)
 * @param absolute      a session ends this long after the login, however busy it is
 * @param sweepInterval how often expired rows are deleted
 */
@ConfigurationProperties("blackhole.public.session")
public record PlayerSessionProperties(@NonNull Duration idle, @NonNull Duration absolute, @NonNull Duration sweepInterval) {

    @ConfigurationInject
    public PlayerSessionProperties {
    }
}
