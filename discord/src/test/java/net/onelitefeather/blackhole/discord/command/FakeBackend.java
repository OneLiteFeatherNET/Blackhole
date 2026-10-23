package net.onelitefeather.blackhole.discord.command;

import net.onelitefeather.blackhole.discord.backend.BackendGateway;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** A backend the test scripts; each test builds its own, so nothing is shared. */
final class FakeBackend implements BackendGateway {

    LinkResult linkResult = new LinkResult.InvalidCode();
    WhoamiResult whoamiResult = new WhoamiResult.Found(List.of());
    UnlinkResult unlinkResult = new UnlinkResult.NotLinked();
    final List<String> confirmed = new ArrayList<>();
    final List<String> unlinked = new ArrayList<>();

    @Override
    public @NotNull LinkResult confirmLink(@NotNull String code, @NotNull String discordUserId) {
        this.confirmed.add(discordUserId + "/" + code);
        return this.linkResult;
    }

    @Override
    public @NotNull WhoamiResult whoami(@NotNull String discordUserId) {
        return this.whoamiResult;
    }

    @Override
    public @NotNull UnlinkResult unlink(@NotNull String discordUserId) {
        this.unlinked.add(discordUserId);
        return this.unlinkResult;
    }
}
