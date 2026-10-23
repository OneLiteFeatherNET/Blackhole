package net.onelitefeather.blackhole.velocity.punish;

import com.google.inject.Inject;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.StaffLinkLookupResponse;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Links a player's Minecraft account to a staff member and tells who it is linked to. The commands
 * translate the outcomes into messages. The code is never logged, not even on failure.
 */
public final class StaffLinkService {

    private static final Logger LOGGER = LoggerFactory.getLogger(StaffLinkService.class);

    /** How a link attempt ended. */
    public sealed interface LinkOutcome {
        /** @param displayName the staff member's name, or {@code null} if the backend has none */
        record Linked(@Nullable String displayName) implements LinkOutcome {
        }

        /** The code is wrong, expired or used; the backend does not say which. */
        record InvalidCode() implements LinkOutcome {
        }

        /** The backend refused or could not be reached; {@code status} is the HTTP status or 0. */
        record Failed(int status) implements LinkOutcome {
        }
    }

    /** What a link status lookup found. */
    public sealed interface WhoamiOutcome {
        /** @param displayName the staff member's name, or {@code null} if the backend has none */
        record Linked(@Nullable String displayName) implements WhoamiOutcome {
        }

        record NotLinked() implements WhoamiOutcome {
        }

        record Failed(int status) implements WhoamiOutcome {
        }
    }

    private final BackendGateway backend;

    @Inject
    public StaffLinkService(@NotNull BackendGateway backend) {
        this.backend = backend;
    }

    /**
     * Confirms the code for the player. Never throws for an expected failure.
     *
     * @param code the code as typed; the backend normalises it
     */
    public LinkOutcome link(@NotNull String code, @NotNull UUID player) {
        try {
            return new LinkOutcome.Linked(this.backend.confirmStaffLink(code, player).getDisplayName());
        } catch (ApiException e) {
            // 400 is the backend's single INVALID_CODE answer; the player's UUID is always sent.
            if (e.getCode() == 400) {
                return new LinkOutcome.InvalidCode();
            }
            LOGGER.error("Linking {} failed with status {}", player, e.getCode());
            return new LinkOutcome.Failed(e.getCode());
        }
    }

    public WhoamiOutcome whoami(@NotNull UUID player) {
        try {
            StaffLinkLookupResponse response = this.backend.lookupStaffLink(player);
            return Boolean.TRUE.equals(response.getLinked())
                    ? new WhoamiOutcome.Linked(response.getDisplayName())
                    : new WhoamiOutcome.NotLinked();
        } catch (ApiException e) {
            LOGGER.error("Looking up the staff link of {} failed with status {}", player, e.getCode());
            return new WhoamiOutcome.Failed(e.getCode());
        }
    }
}
