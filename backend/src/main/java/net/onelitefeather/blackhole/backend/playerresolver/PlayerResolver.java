package net.onelitefeather.blackhole.backend.playerresolver;

import java.util.Optional;

/**
 * A single external name-to-UUID lookup source. Implementations are ordered via
 * {@link io.micronaut.core.annotation.Order} and tried in sequence by
 * {@link PlayerResolverService} until one returns a hit - a failing or disabled resolver must
 * never break the chain, so implementations should turn "not found"/errors into
 * {@link Optional#empty()} rather than throwing.
 */
public interface PlayerResolver {

    Optional<ResolvedPlayer> resolve(String name);
}
