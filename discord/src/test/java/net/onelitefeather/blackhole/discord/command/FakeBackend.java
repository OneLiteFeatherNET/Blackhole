package net.onelitefeather.blackhole.discord.command;

import net.onelitefeather.blackhole.discord.backend.BackendGateway;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.GuardOutcome;
import net.onelitefeather.blackhole.discord.backend.BackendGateway.StaffLink;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** A backend the test scripts; each test builds its own, so nothing is shared. */
final class FakeBackend extends net.onelitefeather.blackhole.discord.testing.UnsupportedBackend {

    LinkResult linkResult = new LinkResult.InvalidCode();
    WhoamiResult whoamiResult = new WhoamiResult.Found(List.of());
    UnlinkResult unlinkResult = new UnlinkResult.NotLinked();
    final List<String> confirmed = new ArrayList<>();
    final List<String> unlinked = new ArrayList<>();
    NotificationResult reportResult = new NotificationResult.NotFound();
    NotificationResult appealResult = new NotificationResult.NotFound();
    int notificationCalls;
    StaffLink staffLinkResult = new StaffLink.NotLinked();
    GuardOutcome guardResult = new GuardOutcome.Unavailable(0);
    final List<String> staffLookups = new ArrayList<>();
    final List<String> guardCalls = new ArrayList<>();

    @Override
    public @NotNull StaffLink staffLink(@NotNull String discordUserId) {
        this.staffLookups.add(discordUserId);
        return this.staffLinkResult;
    }

    @Override
    public @NotNull GuardOutcome setGuardLevel(@NotNull String level, @NotNull String staffOid) {
        this.guardCalls.add(level + "/" + staffOid);
        return this.guardResult;
    }

    @Override
    public @NotNull NotificationResult report(@NotNull String identifier) {
        this.notificationCalls++;
        return this.reportResult;
    }

    @Override
    public @NotNull NotificationResult appeal(@NotNull String identifier) {
        this.notificationCalls++;
        return this.appealResult;
    }

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
