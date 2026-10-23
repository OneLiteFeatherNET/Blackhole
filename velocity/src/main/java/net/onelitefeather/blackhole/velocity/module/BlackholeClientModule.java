package net.onelitefeather.blackhole.velocity.module;

import com.google.inject.AbstractModule;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.velocity.component.PunishmentScreens;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.enforcement.EnforcementActions;
import net.onelitefeather.blackhole.velocity.enforcement.MuteChatDenial;
import net.onelitefeather.blackhole.velocity.enforcement.MuteNotices;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentMirror;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentState;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentSyncHandler;
import net.onelitefeather.blackhole.velocity.evidence.ChatBuffer;
import net.onelitefeather.blackhole.velocity.evidence.EvidenceCounts;
import net.onelitefeather.blackhole.velocity.punish.BackendGateway;
import net.onelitefeather.blackhole.velocity.punish.PunishService;
import net.onelitefeather.blackhole.velocity.redis.RedisSyncService;
import net.onelitefeather.blackhole.velocity.section.SectionRegistry;
import org.jetbrains.annotations.NotNull;

import java.time.Clock;

/**
 * Binds the plugin's long-lived services, created by {@code BlackholeVelocity} at startup, so the
 * commands and listeners can have them injected.
 *
 * @param apiClient   the backend API client
 * @param config      the plugin configuration
 * @param redis       the Redis connection
 * @param sections    the known punishment sections
 * @param mirror      the local copy of online players' punishments
 * @param state       the enforcement questions (login, chat, server connect)
 * @param syncHandler applies sync messages and newly applied punishments
 * @param actions     removes punished players from the network or a server
 * @param backend     the backend calls of the staff commands
 * @param service     the decisions behind the staff commands
 * @param clock       the time source
 * @param screens     builds the texts punished players see
 * @param chatBuffer  the optional buffer of recent public chat
 * @param evidenceCounts the evidence count shown on denial screens
 * @param chatDenial  whether muted signed chat may be denied (SignedVelocity present)
 * @param muteNotices what a muted player is told, once per punishment
 */
public record BlackholeClientModule(
        @NotNull ApiClient apiClient,
        @NotNull BlackholeConfig config,
        @NotNull RedisSyncService redis,
        @NotNull SectionRegistry sections,
        @NotNull PunishmentMirror mirror,
        @NotNull PunishmentState state,
        @NotNull PunishmentSyncHandler syncHandler,
        @NotNull EnforcementActions actions,
        @NotNull BackendGateway backend,
        @NotNull PunishService service,
        @NotNull Clock clock,
        @NotNull PunishmentScreens screens,
        @NotNull ChatBuffer chatBuffer,
        @NotNull EvidenceCounts evidenceCounts,
        @NotNull MuteChatDenial chatDenial,
        @NotNull MuteNotices muteNotices
) {

    /**
     * @return the Guice module binding every service to its instance
     */
    public AbstractModule asModule() {
        return new AbstractModule() {
            @Override
            protected void configure() {
                bind(ApiClient.class).toInstance(apiClient);
                bind(BlackholeConfig.class).toInstance(config);
                bind(RedisSyncService.class).toInstance(redis);
                bind(SectionRegistry.class).toInstance(sections);
                bind(PunishmentMirror.class).toInstance(mirror);
                bind(PunishmentState.class).toInstance(state);
                bind(PunishmentSyncHandler.class).toInstance(syncHandler);
                bind(EnforcementActions.class).toInstance(actions);
                bind(BackendGateway.class).toInstance(backend);
                bind(PunishService.class).toInstance(service);
                bind(Clock.class).toInstance(clock);
                bind(PunishmentScreens.class).toInstance(screens);
                bind(ChatBuffer.class).toInstance(chatBuffer);
                bind(EvidenceCounts.class).toInstance(evidenceCounts);
                bind(MuteChatDenial.class).toInstance(chatDenial);
                bind(MuteNotices.class).toInstance(muteNotices);
            }
        };
    }
}
