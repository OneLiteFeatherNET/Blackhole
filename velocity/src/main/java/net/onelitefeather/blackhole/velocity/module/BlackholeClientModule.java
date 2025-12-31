package net.onelitefeather.blackhole.velocity.module;

import com.google.inject.AbstractModule;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import org.jetbrains.annotations.NotNull;

/**
 * The class represents a module for Guice that binds the {@link ApiClient} to the instance provided in the constructor.
 *
 * @author theEvilReaper
 * @version 1.0.0
 * @since 1.0.0
 */
public final class BlackholeClientModule extends AbstractModule {

    private final ApiClient apiClient;

    /**
     * Create a new instance of the module.
     *
     * @param apiClient the client to bind
     */
    public BlackholeClientModule(@NotNull ApiClient apiClient) {
        this.apiClient = apiClient;
    }

    /**
     * Configures the module and binds the {@link ApiClient} to the instance provided in the constructor.
     * The instance is bound as a singleton.
     */
    @Override
    protected void configure() {
        bind(ApiClient.class).toInstance(this.apiClient);
    }
}
