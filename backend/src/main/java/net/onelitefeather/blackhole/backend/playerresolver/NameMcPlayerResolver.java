package net.onelitefeather.blackhole.backend.playerresolver;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Order;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lowest-priority, best-effort resolver. NameMC has no official API and no stability guarantee
 * on the page it exposes ("may change or disappear at any time" per every third-party wrapper of
 * it) - disabled by default (see {@code blackhole.player-resolver.namemc.enabled}), and any
 * failure here (network, HTML shape change, anything) must fall through to "no match" rather
 * than break the resolver chain or the command that triggered it.
 */
@Singleton
@Order(30)
@Requires(property = "blackhole.player-resolver.namemc.enabled", value = "true", defaultValue = "false")
public class NameMcPlayerResolver implements PlayerResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(NameMcPlayerResolver.class);
    private static final Pattern PROFILE_LINK = Pattern.compile("/profile/([0-9a-fA-F-]{32,36})");

    private final NameMcApiClient client;

    public NameMcPlayerResolver(NameMcApiClient client) {
        this.client = client;
    }

    @Override
    public Optional<ResolvedPlayer> resolve(String name) {
        try {
            String html = this.client.search(name);
            Matcher matcher = PROFILE_LINK.matcher(html);
            if (!matcher.find()) {
                return Optional.empty();
            }
            UUID uuid = UUID.fromString(normalizeToDashed(matcher.group(1)));
            return Optional.of(new ResolvedPlayer(uuid, name, "namemc"));
        } catch (Exception e) {
            LOGGER.debug("NameMC lookup failed for player {}: {}", name, e.getMessage());
            return Optional.empty();
        }
    }

    private static String normalizeToDashed(String uuid) {
        if (uuid.contains("-")) {
            return uuid;
        }
        return uuid.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
    }
}
