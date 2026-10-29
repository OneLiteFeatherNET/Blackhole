package net.onelitefeather.blackhole.backend.publicbans;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.env.Environment;

import java.util.Set;

/**
 * Refuses to start without {@code BLACKHOLE_PUBLIC_BAN_KEY} outside the {@code dev} and {@code test} environments,
 * but only when ban-list clients are configured ({@code BLACKHOLE_BAN_LIST_CLIENTS}). Without clients the list is
 * unreachable, so existing deployments keep starting without the key. The key is never named in a value.
 */
@Context
public class PublicBanKeyGuard {

    private static final Set<String> LOCAL_ENVIRONMENTS = Set.of(Environment.DEVELOPMENT, Environment.TEST);

    public PublicBanKeyGuard(Environment environment,
                             @Value("${blackhole.public-bans.key:}") String key,
                             @Value("${blackhole.ban-list.clients:}") String clients) {
        check(key, clients, environment.getActiveNames());
    }

    /**
     * @throws IllegalStateException when the key is malformed, or is unset while clients are configured outside a local environment
     */
    static void check(String key, String clients, Set<String> activeEnvironments) {
        if (key == null || key.isBlank()) {
            boolean hasClients = clients != null && !clients.isBlank();
            if (hasClients && activeEnvironments.stream().noneMatch(LOCAL_ENVIRONMENTS::contains)) {
                throw new IllegalStateException("BLACKHOLE_PUBLIC_BAN_KEY is not set, but ban-list clients are configured "
                        + "(BLACKHOLE_BAN_LIST_CLIENTS). Outside the dev and test environments the public ban key is required: "
                        + "32 random bytes, base64 (for example: openssl rand -base64 32).");
            }
            return;
        }
        new PublicBanHasher(key);
    }
}
