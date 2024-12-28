package net.onelitefeather.blackhole.velocity.module;

import com.google.inject.AbstractModule;
import net.onelitefeather.blackhole.web.BlackholeClient;
import net.onelitefeather.blackhole.web.BlackholeWebClient;
import org.jetbrains.annotations.NotNull;

/**
 * The class represents a module for Guice that binds the {@link BlackholeWebClient} to the instance provided in the constructor.
 *
 * @author theEvilReaper
 * @version 1.0.0
 * @since 1.0.0
 */
public final class BlackholeClientModule extends AbstractModule {

    private final BlackholeClient webClient;

    /**
     * Create a new instance of the module.
     *
     * @param webClient the client to bind
     */
    public BlackholeClientModule(@NotNull BlackholeClient webClient) {
        this.webClient = webClient;
    }

    /**
     * Configures the module and binds the {@link BlackholeWebClient} to the instance provided in the constructor.
     * The instance is bound as a singleton.
     */
    @Override
    protected void configure() {
        bind(BlackholeClient.class).toInstance(this.webClient);
    }
}
