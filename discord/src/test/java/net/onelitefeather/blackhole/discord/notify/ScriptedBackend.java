package net.onelitefeather.blackhole.discord.notify;

import net.onelitefeather.blackhole.discord.backend.BackendGateway;
import org.jetbrains.annotations.NotNull;

/** A backend whose notification answers the test scripts; the link calls are not used here. */
final class ScriptedBackend extends net.onelitefeather.blackhole.discord.testing.UnsupportedBackend {

    NotificationResult report = new NotificationResult.NotFound();
    NotificationResult appeal = new NotificationResult.NotFound();
    int calls;

    BackendGateway gateway() {
        return this;
    }

    @Override
    public @NotNull NotificationResult report(@NotNull String identifier) {
        this.calls++;
        return this.report;
    }

    @Override
    public @NotNull NotificationResult appeal(@NotNull String identifier) {
        this.calls++;
        return this.appeal;
    }

    @Override
    public @NotNull LinkResult confirmLink(@NotNull String code, @NotNull String discordUserId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull WhoamiResult whoami(@NotNull String discordUserId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull UnlinkResult unlink(@NotNull String discordUserId) {
        throw new UnsupportedOperationException();
    }
}
