package net.onelitefeather.blackhole.discord.gateway;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.onelitefeather.blackhole.discord.command.AccountCommands;
import net.onelitefeather.blackhole.discord.command.Lang;
import net.onelitefeather.blackhole.discord.config.BotConfig;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * The JDA adapter. Intents are minimal: none beyond the always-on guild events. The slash commands and their replies need
 * no message content and no member list, so neither the privileged {@code MESSAGE_CONTENT} nor
 * {@code GUILD_MEMBERS} is requested, the member cache is off and every cache flag is disabled; this
 * keeps the personal data the bot holds in memory to a minimum. Replies are ephemeral. The backend
 * calls run on virtual threads, so a slow backend never blocks the gateway's event thread.
 */
public final class JdaGateway extends ListenerAdapter implements DiscordGateway {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdaGateway.class);

    private final BotConfig config;
    private final AccountCommands commands;
    private final ExecutorService backendCalls = Executors.newVirtualThreadPerTaskExecutor();
    private volatile JDA jda;

    public JdaGateway(@NotNull BotConfig config, @NotNull AccountCommands commands) {
        this.config = config;
        this.commands = commands;
    }

    @Override
    public void start() throws InterruptedException {
        JDA built = JDABuilder.createLight(this.config.discordToken(), EnumSet.noneOf(GatewayIntent.class))
                .disableCache(EnumSet.allOf(CacheFlag.class))
                .setMemberCachePolicy(MemberCachePolicy.NONE)
                .addEventListeners(this)
                .build()
                .awaitReady();
        this.jda = built;
        Guild guild = built.getGuildById(this.config.guildId());
        if (guild == null) {
            built.shutdownNow();
            throw new IllegalStateException("The bot is not a member of the guild " + this.config.guildId()
                    + " (DISCORD_GUILD_ID); invite it first");
        }
        guild.updateCommands().addCommands(commandData()).complete();
        LOGGER.info("Connected to Discord, slash commands registered in guild {}", this.config.guildId());
    }

    static List<CommandData> commandData() {
        CommandData link = Commands.slash("link", "Link your Discord account with a code from the panel or the game")
                .setDescriptionLocalization(DiscordLocale.GERMAN, "Verknüpfe deinen Discord-Account mit einem Code aus dem Panel oder dem Spiel")
                .setContexts(InteractionContextType.GUILD)
                .addOptions(new OptionData(OptionType.STRING, "code", "The code, for example ABCD-EFGH", true)
                        .setDescriptionLocalization(DiscordLocale.GERMAN, "Der Code, zum Beispiel ABCD-EFGH")
                        .setMaxLength(32));
        CommandData unlink = Commands.slash("unlink", "Remove the link to your Discord account")
                .setDescriptionLocalization(DiscordLocale.GERMAN, "Entferne die Verknüpfung deines Discord-Accounts")
                .setContexts(InteractionContextType.GUILD);
        CommandData whoami = Commands.slash("whoami", "Show what your Discord account is linked to")
                .setDescriptionLocalization(DiscordLocale.GERMAN, "Zeige, womit dein Discord-Account verknüpft ist")
                .setContexts(InteractionContextType.GUILD);
        return List.of(link, unlink, whoami);
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        Lang lang = Lang.fromDiscordLocale(event.getUserLocale().getLocale());
        String userId = event.getUser().getId();
        Function<Lang, String> answer = switch (event.getName()) {
            case "link" -> {
                String code = event.getOption("code", null, option -> option.getAsString());
                yield l -> this.commands.link(l, userId, code);
            }
            case "unlink" -> l -> this.commands.unlink(l, userId);
            case "whoami" -> l -> this.commands.whoami(l, userId);
            default -> null;
        };
        if (answer == null) {
            return;
        }
        // Discord wants an acknowledgement within 3 seconds; the answer follows when the backend replied.
        event.deferReply(true).queue(hook -> this.backendCalls.submit(() -> {
            String text;
            try {
                text = answer.apply(lang);
            } catch (RuntimeException e) {
                LOGGER.error("Command {} failed", event.getName(), e);
                text = lang == Lang.DE ? "Es ist ein Fehler aufgetreten. Bitte versuche es später erneut."
                        : "Something went wrong. Please try again later.";
            }
            hook.editOriginal(text).queue();
        }));
    }

    @Override
    public boolean connected() {
        JDA current = this.jda;
        return current != null && current.getStatus() == JDA.Status.CONNECTED;
    }

    @Override
    public void close() {
        JDA current = this.jda;
        if (current != null) {
            current.shutdown();
        }
        this.backendCalls.shutdown();
    }
}
