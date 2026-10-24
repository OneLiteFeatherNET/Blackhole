package net.onelitefeather.blackhole.discord.gateway;

/**
 * What the bot needs from Discord, behind an interface so everything above it is tested with a fake and
 * JDA types stay inside {@link JdaGateway}. Later phases (team notifications, tickets) add their calls
 * here, such as posting and editing a message.
 */
public interface DiscordGateway extends AutoCloseable {

    /**
     * Connects to Discord and registers the slash commands in the guild.
     *
     * @throws InterruptedException if interrupted while waiting for the connection
     * @throws IllegalStateException if the bot is not a member of the configured guild
     */
    void start() throws InterruptedException;

    /** @return whether the gateway connection is up */
    boolean connected();

    @Override
    void close();
}
