package net.onelitefeather.blackhole.velocity.punish;

import com.google.inject.Inject;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.DiscordLinkCodeResponse;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.UUID;

/**
 * Asks the backend for a one-time code with which a player links their Discord account (typed as
 * {@code /link <code>} in Discord). The backend only ever sees the hashed owner. The code is never
 * logged.
 */
public final class DiscordLinkService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordLinkService.class);

    /** How a code request ended. */
    public sealed interface CodeOutcome {
        /**
         * @param code    the code as shown to the player, {@code ABCD-EFGH}
         * @param minutes how long it stays valid, rounded up, at least 1
         */
        record Issued(@NotNull String code, int minutes) implements CodeOutcome {
        }

        /** The backend refused or could not be reached; {@code status} is the HTTP status or 0. */
        record Failed(int status) implements CodeOutcome {
        }
    }

    private final BackendGateway backend;
    private final Clock clock;

    @Inject
    public DiscordLinkService(@NotNull BackendGateway backend) {
        this(backend, Clock.systemUTC());
    }

    DiscordLinkService(@NotNull BackendGateway backend, @NotNull Clock clock) {
        this.backend = backend;
        this.clock = clock;
    }

    /** Issues a code for the player. Never throws for an expected failure. */
    public CodeOutcome issueCode(@NotNull UUID player) {
        try {
            DiscordLinkCodeResponse response = this.backend.issueDiscordLinkCode(UUIDConverter.convertToSHA(player));
            long remaining = Math.max(0, response.getExpiresAt() - this.clock.millis());
            int minutes = (int) Math.max(1, (remaining + 59_999) / 60_000);
            return new CodeOutcome.Issued(response.getCode(), minutes);
        } catch (ApiException e) {
            LOGGER.error("Issuing a Discord link code failed with status {}", e.getCode());
            return new CodeOutcome.Failed(e.getCode());
        }
    }
}
