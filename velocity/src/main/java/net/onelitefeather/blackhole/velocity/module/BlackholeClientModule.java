package net.onelitefeather.blackhole.velocity.module;

import com.google.inject.AbstractModule;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.redis.RedisSyncService;
import org.jetbrains.annotations.NotNull;

/**
 * The class represents a module for Guice that binds the {@link ApiClient}, {@link BlackholeConfig}
 * and {@link RedisSyncService} to the instances provided in the constructor.
 *
 * @author theEvilReaper
 * @version 1.0.0
 * @since 1.0.0
 */
public final class BlackholeClientModule extends AbstractModule {

    private final ApiClient apiClient;
    private final BlackholeConfig config;
    private final RedisSyncService redisSyncService;

    /**
     * Create a new instance of the module.
     *
     * @param apiClient        the client to bind
     * @param config           the configuration to bind
     * @param redisSyncService the Redis sync service to bind
     */
    public BlackholeClientModule(@NotNull ApiClient apiClient, @NotNull BlackholeConfig config, @NotNull RedisSyncService redisSyncService) {
        this.apiClient = apiClient;
        this.config = config;
        this.redisSyncService = redisSyncService;
    }

    /**
     * Configures the module and binds the {@link ApiClient}, {@link BlackholeConfig} and
     * {@link RedisSyncService} to the instances provided in the constructor. All instances are
     * bound as singletons.
     */
    @Override
    protected void configure() {
        bind(ApiClient.class).toInstance(this.apiClient);
        bind(BlackholeConfig.class).toInstance(this.config);
        bind(RedisSyncService.class).toInstance(this.redisSyncService);
    }
}
