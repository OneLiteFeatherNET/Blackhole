package net.onelitefeather.blackhole.discord;

import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.discord.auth.ClientCredentialsTokenProvider;
import net.onelitefeather.blackhole.discord.auth.JdkTokenTransport;
import net.onelitefeather.blackhole.discord.auth.TokenSource;
import net.onelitefeather.blackhole.discord.backend.ApiClients;
import net.onelitefeather.blackhole.discord.backend.BackendGateway;
import net.onelitefeather.blackhole.discord.backend.HttpBackendGateway;
import net.onelitefeather.blackhole.discord.command.AccountCommands;
import net.onelitefeather.blackhole.discord.config.BotConfig;
import net.onelitefeather.blackhole.discord.gateway.DiscordGateway;
import net.onelitefeather.blackhole.discord.gateway.HealthServer;
import net.onelitefeather.blackhole.discord.gateway.JdaGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;

/**
 * Starts the Blackhole Discord bot: reads the environment, authenticates to the backend with the client
 * credentials grant (role {@code Blackhole.Discord}), connects to Discord and registers the slash
 * commands. The bot keeps no state of its own.
 */
public final class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        BotConfig config;
        try {
            config = BotConfig.fromEnvironment(System::getenv);
        } catch (BotConfig.ConfigException e) {
            e.problems().forEach(problem -> LOGGER.error("Configuration: {}", problem));
            System.exit(2);
            return;
        }
        LOGGER.info("Starting the Blackhole Discord bot with {}", config);

        TokenSource tokens = new ClientCredentialsTokenProvider(config.auth(), new JdkTokenTransport(), Clock.systemUTC());
        ApiClient client = ApiClients.create(config.backendUrl(), tokens);
        BackendGateway backend = new HttpBackendGateway(client);
        DiscordGateway discord = new JdaGateway(config, new AccountCommands(backend));

        HealthServer health = null;
        if (config.healthPort() > 0) {
            health = new HealthServer(config.healthPort(), discord::connected);
            health.start();
        }
        HealthServer healthServer = health;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            discord.close();
            if (healthServer != null) {
                healthServer.close();
            }
        }, "shutdown"));

        try {
            discord.start();
        } catch (IllegalStateException e) {
            LOGGER.error("{}", e.getMessage());
            discord.close();
            System.exit(3);
        }
    }
}
