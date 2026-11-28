package net.onelitefeather.blackhole.velocity.listener;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import net.onelitefeather.blackhole.velocity.enforcement.WarningHandler;
import org.jetbrains.annotations.NotNull;

/**
 * Shows a player's pending warnings as soon as they join. A login is never refused because of a warning.
 */
public final class WarningListener {

    private final WarningHandler warnings;

    @Inject
    public WarningListener(@NotNull WarningHandler warnings) {
        this.warnings = warnings;
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        this.warnings.deliverLater(event.getPlayer());
    }
}
