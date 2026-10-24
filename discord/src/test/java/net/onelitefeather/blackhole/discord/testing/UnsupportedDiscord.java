package net.onelitefeather.blackhole.discord.testing;

import net.onelitefeather.blackhole.discord.gateway.DiscordGateway;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** A Discord for tests that only use part of the interface: every call it does not override fails loudly. */
public abstract class UnsupportedDiscord implements DiscordGateway {

    @Override
    public void listener(@NotNull Listener listener) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long selfId() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void ensureAppealPanel(long channelId, @NotNull String title, @NotNull String body, @NotNull String buttonLabel, @NotNull String marker) throws GatewayException {
        throw new UnsupportedOperationException();
    }

    @Override
    public long createChannel(@NotNull NewChannel channel) throws GatewayException {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull Optional<Long> findChannelByTopic(@NotNull List<Long> categoryIds, @NotNull String marker) throws GatewayException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean channelExists(long channelId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void moveChannel(long channelId, long categoryId) throws GatewayException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteChannel(long channelId) throws GatewayException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setMemberAccess(long channelId, @NotNull MemberAccess access) throws GatewayException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeMemberAccess(long channelId, long userId) throws GatewayException {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull Set<Long> memberAccess(long channelId) throws GatewayException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean hasRole(long userId, long roleId) throws GatewayException {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull MessageRef postRelay(long channelId, @NotNull String label, @NotNull String text, @NotNull String ref) throws GatewayException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void editRelay(@NotNull MessageRef message, @NotNull String text) throws GatewayException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteMessage(@NotNull MessageRef message) throws GatewayException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void notice(long channelId, @NotNull String text) throws GatewayException {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull MessageRef postPlain(long channelId, @NotNull String text) throws GatewayException {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull MessageRef postFile(long channelId, String text, @NotNull String fileName, byte @NotNull [] data) throws GatewayException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void react(@NotNull MessageRef message, @NotNull String emoji) throws GatewayException {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull List<IncomingMessage> history(long channelId, long afterId, int limit) throws GatewayException {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull Optional<Instant> lastActivity(long channelId) throws GatewayException {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull Set<Permission> missingPermissions(long categoryId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean roleCanView(long categoryId, long roleId) {
        throw new UnsupportedOperationException();
    }
}
