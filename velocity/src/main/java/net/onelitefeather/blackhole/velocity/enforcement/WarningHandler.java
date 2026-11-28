package net.onelitefeather.blackhole.velocity.enforcement;

import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.WarningResponse;
import net.onelitefeather.blackhole.velocity.component.WarningMessages;
import net.onelitefeather.blackhole.velocity.punish.BackendGateway;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Shows warnings to players and records their confirmation. A warning is shown in game as soon as it is
 * given to a player connected to this proxy, and again on each login while it is still pending. Showing a
 * warning never confirms it: only the player's acknowledge command does, and that is posted over HTTP with
 * the proxy's own token (the proxy only listens on the bus).
 *
 * <p>Nothing here refuses a login or touches the punishment cache. A failed read is logged without player
 * names; the reason falls back to the default text, and a failed pending read shows nothing, so the warning
 * waits for the next login.</p>
 *
 * <p>The blocking methods make backend calls and must not run on the NATS or the server thread; the
 * {@code *Later} methods and {@link #async} hand the work to the executor.</p>
 */
public final class WarningHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(WarningHandler.class);

    private final BackendGateway backend;
    private final Executor async;

    /**
     * @param async runs the work of the {@code *Later} methods and {@link #async} off the calling thread
     */
    public WarningHandler(@NotNull BackendGateway backend, @NotNull Executor async) {
        this.backend = backend;
        this.async = async;
    }

    /** Runs the task on the async executor. */
    public void async(@NotNull Runnable task) {
        this.async.execute(task);
    }

    /** Shows the pending warnings of a player who just joined, on the async executor. */
    public void deliverLater(@NotNull Player player) {
        this.async.execute(() -> deliverPending(player));
    }

    /** Shows each pending warning of the player once for this login. A failed read shows nothing. */
    public void deliverPending(@NotNull Player player) {
        List<WarningResponse> pending;
        try {
            pending = this.backend.pendingWarnings(ownerOf(player));
        } catch (ApiException | RuntimeException e) {
            LOGGER.warn("Pending warnings could not be read at login, they are shown at the next login ({})", e.getClass().getSimpleName());
            return;
        }
        for (WarningResponse warning : pending) {
            player.sendMessage(WarningMessages.warning(warning.getReason(), warning.getId()));
        }
    }

    /**
     * Shows a warning to its owner if they are connected to this proxy; nobody else is told and the backend
     * is not asked for anything when they are not.
     *
     * @param online the players connected to this proxy
     */
    public void warned(@NotNull WarningMessage message, @NotNull Collection<? extends Player> online) {
        Optional<? extends Player> owner = online.stream().filter(player -> ownerOf(player).equals(message.owner())).findFirst();
        if (owner.isEmpty()) {
            return;
        }
        owner.get().sendMessage(WarningMessages.warning(reasonOf(message.warningId()), message.warningId()));
    }

    /** Confirms a warning for the player on the async executor. */
    public void acknowledgeLater(@NotNull Player player, @NotNull String id) {
        this.async.execute(() -> acknowledge(player, id));
    }

    /**
     * Confirms a warning for the player, but only when the warning belongs to them: the owner is checked
     * before anything is posted, so a player can never confirm somebody else's warning.
     */
    public void acknowledge(@NotNull Player player, @NotNull String id) {
        String owner = ownerOf(player);
        WarningResponse warning;
        try {
            warning = this.backend.warning(id);
        } catch (ApiException e) {
            player.sendMessage(e.getCode() == 404 ? WarningMessages.unknown() : WarningMessages.failed());
            return;
        } catch (RuntimeException e) {
            player.sendMessage(WarningMessages.failed());
            return;
        }
        if (!owner.equals(warning.getOwner())) {
            player.sendMessage(WarningMessages.notYours());
            return;
        }
        try {
            this.backend.acknowledgeWarning(id, owner);
            player.sendMessage(WarningMessages.acknowledged());
        } catch (ApiException | RuntimeException e) {
            LOGGER.warn("Warning {} could not be acknowledged ({})", id, e.getClass().getSimpleName());
            player.sendMessage(WarningMessages.failed());
        }
    }

    /** @return the reason of the warning, null if it cannot be read: the screen then shows the default text */
    private @Nullable String reasonOf(String id) {
        try {
            WarningResponse warning = this.backend.warning(id);
            return warning == null ? null : warning.getReason();
        } catch (ApiException | RuntimeException e) {
            LOGGER.warn("Could not read the warning {}, showing the default text ({})", id, e.getClass().getSimpleName());
            return null;
        }
    }

    private static @NotNull String ownerOf(Player player) {
        return UUIDConverter.convertToSHA(player.getUniqueId());
    }
}
