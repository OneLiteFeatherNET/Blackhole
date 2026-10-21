package net.onelitefeather.blackhole.velocity.punish;

import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * Turns the player argument of a staff command into a target: a connected player, a UUID typed as
 * such, or - for offline players - a name the backend's player resolver knows.
 */
public final class TargetResolver {

    /**
     * A resolved punishment target. {@code onlinePlayer} is {@code null} for an offline target;
     * callers must not message or disconnect a player directly in that case.
     */
    public record ResolvedTarget(@NotNull UUID uuid, @NotNull String name, @Nullable Player onlinePlayer) {

        /** @return the SHA-512 owner hash the backend identifies the player by */
        public String ownerHash() {
            return UUIDConverter.convertToSHA(this.uuid);
        }
    }

    /** Looks up a connected player by name. */
    public interface OnlineLookup {
        Optional<Player> find(String name);
    }

    /** A player known to the backend's resolver chain. */
    public record Known(UUID uuid, String name) {
    }

    /** Asks the backend to resolve an offline player's name. */
    public interface NameLookup {
        Optional<Known> resolve(String name) throws ApiException;
    }

    private final OnlineLookup online;
    private final NameLookup names;

    public TargetResolver(OnlineLookup online, NameLookup names) {
        this.online = online;
        this.names = names;
    }

    /**
     * @param input what the staff member typed
     * @return the target
     * @throws IllegalArgumentException if the input names no known player
     */
    public ResolvedTarget resolve(String input) {
        Optional<Player> connected = this.online.find(input);
        if (connected.isPresent()) {
            Player player = connected.get();
            return new ResolvedTarget(player.getUniqueId(), player.getUsername(), player);
        }
        try {
            UUID literal = UUID.fromString(input);
            return new ResolvedTarget(literal, input, null);
        } catch (IllegalArgumentException ignored) {
            // not a UUID: ask the backend
        }
        try {
            Optional<Known> known = this.names.resolve(input);
            if (known.isPresent()) {
                return new ResolvedTarget(known.get().uuid(), known.get().name(), null);
            }
        } catch (ApiException e) {
            // handled as unknown below
        }
        throw new IllegalArgumentException("Player(%s) not found".formatted(input));
    }
}
