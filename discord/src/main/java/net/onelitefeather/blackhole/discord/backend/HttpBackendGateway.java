package net.onelitefeather.blackhole.discord.backend;

import net.onelitefeather.blackhole.client.api.DiscordLinkApi;
import net.onelitefeather.blackhole.client.api.DiscordNotificationsApi;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.DiscordAppealNotification;
import net.onelitefeather.blackhole.client.model.DiscordLinkConfirmRequest;
import net.onelitefeather.blackhole.client.model.DiscordLinkConfirmResponse;
import net.onelitefeather.blackhole.client.model.DiscordLinkKind;
import net.onelitefeather.blackhole.client.model.DiscordLinkRemovedResponse;
import net.onelitefeather.blackhole.client.model.DiscordLinkWhoamiResponse;
import net.onelitefeather.blackhole.client.model.DiscordReportNotification;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * {@link BackendGateway} over the generated client. The code and the Discord user id are never logged.
 */
public final class HttpBackendGateway implements BackendGateway {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpBackendGateway.class);

    private final DiscordLinkApi api;
    private final DiscordNotificationsApi notifications;

    public HttpBackendGateway(@NotNull ApiClient client) {
        this.api = new DiscordLinkApi(client);
        this.notifications = new DiscordNotificationsApi(client);
    }

    @Override
    public @NotNull LinkResult confirmLink(@NotNull String code, @NotNull String discordUserId) {
        try {
            DiscordLinkConfirmResponse response = this.api.confirmDiscordLink(
                    new DiscordLinkConfirmRequest().code(code).discordUserId(discordUserId));
            return new LinkResult.Linked(kind(response.getKind()), response.getDisplayName());
        } catch (ApiException e) {
            // 400 is the backend's single INVALID_CODE answer; the Discord user id is always well formed here.
            if (e.getCode() == 400) {
                return new LinkResult.InvalidCode();
            }
            LOGGER.error("Confirming a Discord link failed with status {}", e.getCode());
            return new LinkResult.Unavailable(e.getCode());
        }
    }

    @Override
    public @NotNull WhoamiResult whoami(@NotNull String discordUserId) {
        try {
            DiscordLinkWhoamiResponse response = this.api.lookupDiscordLink(discordUserId);
            List<LinkEntry> links = response.getLinks().stream()
                    .map(entry -> new LinkEntry(kind(entry.getKind()), entry.getDisplayName()))
                    .toList();
            return new WhoamiResult.Found(links);
        } catch (ApiException e) {
            LOGGER.error("Looking up a Discord link failed with status {}", e.getCode());
            return new WhoamiResult.Unavailable(e.getCode());
        }
    }

    @Override
    public @NotNull UnlinkResult unlink(@NotNull String discordUserId) {
        try {
            DiscordLinkRemovedResponse response = this.api.unlinkDiscordUser(discordUserId);
            return new UnlinkResult.Removed(response.getRemoved() == null ? 0 : response.getRemoved());
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                return new UnlinkResult.NotLinked();
            }
            LOGGER.error("Unlinking a Discord user failed with status {}", e.getCode());
            return new UnlinkResult.Unavailable(e.getCode());
        }
    }

    @Override
    public @NotNull NotificationResult report(@NotNull String identifier) {
        try {
            DiscordReportNotification r = this.notifications.getReportNotification(java.util.UUID.fromString(identifier));
            return new NotificationResult.Found(new Notification(NotificationKind.REPORT, r.getIdentifier().toString(), null,
                    r.getPlayerName(), r.getPlayerRef(), r.getCategory(), r.getDescription(), r.getStatus(),
                    r.getCreatedAt(), r.getUpdatedAt()));
        } catch (IllegalArgumentException e) {
            return new NotificationResult.NotFound();
        } catch (ApiException e) {
            return unavailableOrMissing("report", e);
        }
    }

    @Override
    public @NotNull NotificationResult appeal(@NotNull String identifier) {
        try {
            DiscordAppealNotification a = this.notifications.getAppealNotification(java.util.UUID.fromString(identifier));
            return new NotificationResult.Found(new Notification(NotificationKind.APPEAL, a.getIdentifier().toString(),
                    a.getPunishmentIdentifier(), a.getPlayerName(), a.getPlayerRef(), a.getSection(), a.getStatement(),
                    a.getStatus(), a.getCreatedAt(), a.getUpdatedAt()));
        } catch (IllegalArgumentException e) {
            return new NotificationResult.NotFound();
        } catch (ApiException e) {
            return unavailableOrMissing("appeal", e);
        }
    }

    private static NotificationResult unavailableOrMissing(String what, ApiException e) {
        if (e.getCode() == 404) {
            return new NotificationResult.NotFound();
        }
        LOGGER.error("Reading the {} notification failed with status {}", what, e.getCode());
        return new NotificationResult.Unavailable(e.getCode());
    }

    private static LinkKind kind(DiscordLinkKind kind) {
        return kind == DiscordLinkKind.PLAYER ? LinkKind.PLAYER : LinkKind.STAFF;
    }
}
