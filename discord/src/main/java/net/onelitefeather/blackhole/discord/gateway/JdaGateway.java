package net.onelitefeather.blackhole.discord.gateway;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.MessageType;
import net.dv8tion.jda.api.entities.PermissionOverride;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.attribute.ICategorizableChannel;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.channel.ChannelDeleteEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.MessageUpdateEvent;
import net.dv8tion.jda.api.events.session.SessionRecreateEvent;
import net.dv8tion.jda.api.events.session.SessionResumeEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.TimeUtil;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.onelitefeather.blackhole.discord.command.AccountCommands;
import net.onelitefeather.blackhole.discord.command.AppealCommands;
import net.onelitefeather.blackhole.discord.command.Lang;
import net.onelitefeather.blackhole.discord.command.StaffButtonHandler;
import net.onelitefeather.blackhole.discord.config.BotConfig;
import net.onelitefeather.blackhole.discord.ticket.StaffControlIds;
import net.onelitefeather.blackhole.discord.ticket.StaffControlIds.Action;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The JDA adapter, and the only class that touches Discord's types.
 *
 * <p>Intents are {@code GUILDS}, {@code GUILD_MESSAGES} and the privileged {@code MESSAGE_CONTENT} (the text of ticket
 * messages is the record). Not {@code GUILD_MEMBERS} or {@code GUILD_PRESENCES}: the member cache is off, every cache flag is
 * disabled, and an author's roles come from the message event or a REST lookup on demand, which keeps the personal data the bot
 * holds in memory to a minimum. Slash command and modal replies are ephemeral. Backend calls from interactions run on virtual
 * threads, so a slow backend never blocks the gateway's event thread.</p>
 */
public final class JdaGateway extends ListenerAdapter implements DiscordGateway {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdaGateway.class);
    static final String APPEAL_BUTTON = "appeal:open";
    static final String APPEAL_MODAL = "appeal:modal";
    private static final String FIELD_BAN_ID = "banid";
    private static final String FIELD_STATEMENT = "statement";
    private static final String FIELD_MESSAGE = "message";
    private static final int MAX_EMBED_TEXT = 4000;

    private final BotConfig config;
    private final AccountCommands commands;
    private volatile AppealCommands appeals;
    private volatile StaffButtonHandler staff;
    private final ExecutorService backendCalls = Executors.newVirtualThreadPerTaskExecutor();
    private volatile Listener listener = new Listener() {
        @Override
        public void onReady() {
        }

        @Override
        public void onMessage(@NotNull IncomingMessage message) {
        }

        @Override
        public void onMessageEdit(@NotNull IncomingMessage message) {
        }

        @Override
        public void onMessageDelete(long channelId, long messageId) {
        }

        @Override
        public void onChannelDelete(long channelId) {
        }
    };
    private volatile JDA jda;

    public JdaGateway(@NotNull BotConfig config, @NotNull AccountCommands commands) {
        this.config = config;
        this.commands = commands;
    }

    /** Binds the appeal commands; they need the ticket lifecycle, which needs this gateway, so they arrive after construction and before {@link #start()}. */
    public void appeals(@NotNull AppealCommands appeals) {
        this.appeals = appeals;
    }

    /** Binds the staff ticket controls; without it the staff buttons do nothing (the feature flag is off). */
    public void staff(@NotNull StaffButtonHandler staff) {
        this.staff = staff;
    }

    /** The intents the bot connects with; a test pins them so a privileged one is never added by accident. */
    static EnumSet<GatewayIntent> intents() {
        return EnumSet.of(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT);
    }

    /** The permissions a bot needs in a category, as Discord's. */
    static net.dv8tion.jda.api.Permission jda(Permission permission) {
        return switch (permission) {
            case VIEW -> net.dv8tion.jda.api.Permission.VIEW_CHANNEL;
            case SEND -> net.dv8tion.jda.api.Permission.MESSAGE_SEND;
            case READ_HISTORY -> net.dv8tion.jda.api.Permission.MESSAGE_HISTORY;
            case EMBED_LINKS -> net.dv8tion.jda.api.Permission.MESSAGE_EMBED_LINKS;
            case ATTACH_FILES -> net.dv8tion.jda.api.Permission.MESSAGE_ATTACH_FILES;
            case MANAGE_CHANNEL -> net.dv8tion.jda.api.Permission.MANAGE_CHANNEL;
            case MANAGE_PERMISSIONS -> net.dv8tion.jda.api.Permission.MANAGE_PERMISSIONS;
            case MANAGE_MESSAGES -> net.dv8tion.jda.api.Permission.MESSAGE_MANAGE;
        };
    }

    private static Set<net.dv8tion.jda.api.Permission> jda(Set<Permission> permissions) {
        return permissions.stream().map(JdaGateway::jda).collect(Collectors.toCollection(() -> EnumSet.noneOf(net.dv8tion.jda.api.Permission.class)));
    }

    @Override
    public void listener(@NotNull Listener listener) {
        this.listener = listener;
    }

    @Override
    public void start() throws InterruptedException {
        JDA built = JDABuilder.createLight(this.config.discordToken(), intents())
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
        this.listener.onReady();
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
        CommandData appeal = Commands.slash("appeal", "Appeal a ban in a private channel")
                .setDescriptionLocalization(DiscordLocale.GERMAN, "Lege in einem privaten Kanal Einspruch gegen einen Bann ein")
                .setContexts(InteractionContextType.GUILD);
        return List.of(link, unlink, whoami, appeal);
    }

    // ---- interactions ----

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        Lang lang = Lang.fromDiscordLocale(event.getUserLocale().getLocale());
        if (event.getName().equals("appeal")) {
            event.replyModal(appealModal(lang)).queue();
            return;
        }
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
                text = failureText(lang);
            }
            hook.editOriginal(text).queue();
        }));
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        if (APPEAL_BUTTON.equals(event.getComponentId())) {
            event.replyModal(appealModal(Lang.fromDiscordLocale(event.getUserLocale().getLocale()))).queue();
        } else if (StaffControlIds.isStaffId(event.getComponentId())) {
            staffPress(event);
        }
    }

    /**
     * A staff control press. Accept and Reject answer with their modal at once (Discord allows three seconds for the first
     * reply); every other press is acknowledged first and answered after the backend replied.
     */
    private void staffPress(ButtonInteractionEvent event) {
        StaffButtonHandler handler = this.staff;
        if (handler == null) {
            return;
        }
        String customId = event.getComponentId();
        boolean modal = StaffControlIds.parse(customId).map(press -> press.action() == Action.ACCEPT || press.action() == Action.REJECT)
                .orElse(false);
        long channelId = event.getChannel().getIdLong();
        long userId = event.getUser().getIdLong();
        boolean staffRole = event.getMember() != null && holdsStaffRole(event.getMember());
        if (modal) {
            StaffButtonHandler.Reply reply = new InteractionReply(event, null);
            this.backendCalls.submit(() -> handler.onPress(new StaffButtonHandler.Press(channelId, userId, customId, staffRole, reply)));
            return;
        }
        event.deferReply(true).queue(hook -> this.backendCalls.submit(() -> handler.onPress(
                new StaffButtonHandler.Press(channelId, userId, customId, staffRole, new InteractionReply(event, hook)))));
    }

    /** The decision modal of an appeal: the player message, required for a rejection. */
    private Modal decisionModal(Action decision, String ticketId) {
        boolean reject = decision == Action.REJECT;
        TextInput message = TextInput.create(FIELD_MESSAGE, TextInputStyle.PARAGRAPH).setRequired(reject).setMaxLength(2000).build();
        return Modal.create(StaffControlIds.id(decision, ticketId), reject ? "Reject the appeal" : "Accept the appeal")
                .addComponents(Label.of("Message to the player (shown to them)", message))
                .build();
    }

    @Override
    public void onModalInteraction(@NotNull ModalInteractionEvent event) {
        if (StaffControlIds.isStaffId(event.getModalId())) {
            staffSubmit(event);
            return;
        }
        if (!APPEAL_MODAL.equals(event.getModalId())) {
            return;
        }
        Lang lang = Lang.fromDiscordLocale(event.getUserLocale().getLocale());
        // The values are read once and handed on; they are never logged and never put into a reply.
        String banId = event.getValue(FIELD_BAN_ID) == null ? null : event.getValue(FIELD_BAN_ID).getAsString();
        String statement = event.getValue(FIELD_STATEMENT) == null ? null : event.getValue(FIELD_STATEMENT).getAsString();
        String userId = event.getUser().getId();
        String name = event.getUser().getEffectiveName();
        event.deferReply(true).queue(hook -> this.backendCalls.submit(() -> {
            String text;
            try {
                text = this.appeals.open(lang, userId, name, banId, statement);
            } catch (RuntimeException e) {
                LOGGER.error("Opening an appeal failed: {}", e.getClass().getName());
                text = failureText(lang);
            }
            hook.editOriginal(text).queue();
        }));
    }

    private Modal appealModal(Lang lang) {
        TextInput banId = TextInput.create(FIELD_BAN_ID, TextInputStyle.SHORT).setRequired(true).setMinLength(4).setMaxLength(16).build();
        TextInput statement = TextInput.create(FIELD_STATEMENT, TextInputStyle.PARAGRAPH).setRequired(false).setMaxLength(1500).build();
        return Modal.create(APPEAL_MODAL, this.appeals.modalTitle(lang))
                .addComponents(Label.of(this.appeals.banIdLabel(lang), banId), Label.of(this.appeals.statementLabel(lang), statement))
                .build();
    }

    /** A staff decision submitted in the modal; the modal's text is read once and handed on, never logged. */
    private void staffSubmit(ModalInteractionEvent event) {
        StaffButtonHandler handler = this.staff;
        Optional<StaffControlIds.Press> press = StaffControlIds.parse(event.getModalId());
        if (handler == null || press.isEmpty()) {
            return;
        }
        String message = event.getValue(FIELD_MESSAGE) == null ? null : event.getValue(FIELD_MESSAGE).getAsString();
        long channelId = event.getChannel().getIdLong();
        long userId = event.getUser().getIdLong();
        boolean staffRole = event.getMember() != null && holdsStaffRole(event.getMember());
        StaffControlIds.Press decided = press.get();
        event.deferReply(true).queue(hook -> this.backendCalls.submit(() -> handler.onDecision(new StaffButtonHandler.Submit(channelId, userId,
                decided.action(), decided.ticketId(), message, staffRole, new InteractionReply(null, hook)))));
    }

    /**
     * The one reply to a staff interaction: a deferred hook once acknowledged, otherwise the interaction itself (which may also
     * open the decision modal).
     */
    private final class InteractionReply implements StaffButtonHandler.Reply {

        private final ButtonInteractionEvent button;
        private final InteractionHook hook;

        InteractionReply(ButtonInteractionEvent button, InteractionHook hook) {
            this.button = button;
            this.hook = hook;
        }

        @Override
        public void text(@NotNull String text) {
            if (this.hook != null) {
                this.hook.editOriginal(text).queue();
            } else {
                this.button.reply(text).setEphemeral(true).queue();
            }
        }

        @Override
        public void decisionModal(@NotNull StaffControlIds.Action decision, @NotNull String ticketId) {
            this.button.replyModal(JdaGateway.this.decisionModal(decision, ticketId)).queue();
        }
    }

    private String failureText(Lang lang) {
        return this.appeals.generic(lang);
    }

    // ---- gateway events ----

    @Override
    public void onSessionResume(@NotNull SessionResumeEvent event) {
        this.listener.onReady();
    }

    @Override
    public void onSessionRecreate(@NotNull SessionRecreateEvent event) {
        this.listener.onReady();
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (event.isFromGuild() && supported(event.getMessage())) {
            this.listener.onMessage(incoming(event.getMessage(), event.getMember() != null && holdsStaffRole(event.getMember())));
        }
    }

    @Override
    public void onMessageUpdate(@NotNull MessageUpdateEvent event) {
        if (event.isFromGuild() && supported(event.getMessage())) {
            this.listener.onMessageEdit(incoming(event.getMessage(), event.getMember() != null && holdsStaffRole(event.getMember())));
        }
    }

    @Override
    public void onMessageDelete(@NotNull MessageDeleteEvent event) {
        if (event.isFromGuild()) {
            this.listener.onMessageDelete(event.getChannel().getIdLong(), event.getMessageIdLong());
        }
    }

    @Override
    public void onChannelDelete(@NotNull ChannelDeleteEvent event) {
        this.listener.onChannelDelete(event.getChannel().getIdLong());
    }

    private static boolean supported(Message message) {
        return message.getType() == MessageType.DEFAULT || message.getType() == MessageType.INLINE_REPLY;
    }

    private boolean holdsStaffRole(Member member) {
        return member.getRoles().stream().anyMatch(role -> role.getIdLong() == this.config.staffRoleId());
    }

    private IncomingMessage incoming(Message message, boolean staffRole) {
        long self = this.jda == null ? 0 : this.jda.getSelfUser().getIdLong();
        long author = message.getAuthor().getIdLong();
        boolean isSelf = author == self;
        String name = message.getMember() != null ? message.getMember().getEffectiveName() : message.getAuthor().getEffectiveName();
        List<IncomingAttachment> attachments = new ArrayList<>();
        for (Message.Attachment a : message.getAttachments()) {
            attachments.add(new IncomingAttachment("file", a.getId(), a.getFileName(), a.getContentType(), (long) a.getSize(),
                    a.isImage() ? a.getWidth() : null, a.isImage() ? a.getHeight() : null, a.getUrl()));
        }
        message.getStickers().forEach(sticker -> attachments.add(new IncomingAttachment("sticker", null, sticker.getName(), null, null, null, null)));
        List<IncomingEmbed> embeds = new ArrayList<>();
        for (MessageEmbed embed : message.getEmbeds()) {
            embeds.add(new IncomingEmbed(embed.getType().name().toLowerCase(java.util.Locale.ROOT), host(embed.getUrl())));
        }
        String relayRef = null;
        if (isSelf && !message.getEmbeds().isEmpty() && message.getEmbeds().getFirst().getFooter() != null) {
            String footer = message.getEmbeds().getFirst().getFooter().getText();
            relayRef = footer != null && footer.startsWith("ref ") ? footer : null;
        }
        return new IncomingMessage(message.getIdLong(), message.getChannelIdLong(), categoryOf(message), publicView(message), author, name,
                message.getAuthor().isBot(), isSelf, staffRole, message.getTimeCreated().toInstant(),
                message.getTimeEdited() == null ? null : message.getTimeEdited().toInstant(), message.getContentDisplay(), attachments, embeds, relayRef);
    }

    private static String host(String url) {
        if (url == null) {
            return null;
        }
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static long categoryOf(Message message) {
        if (!message.isFromGuild()) {
            return 0;
        }
        return categoryOf(message.getGuildChannel());
    }

    /** Whether {@code @everyone} can see the channel (its parent for a thread), taking the channel's overwrites into account. */
    private static boolean publicView(Message message) {
        if (!message.isFromGuild()) {
            return false;
        }
        GuildChannel channel = message.getGuildChannel();
        GuildChannel base = channel instanceof ThreadChannel thread ? thread.getParentChannel() : channel;
        return message.getGuild().getPublicRole().hasPermission(base, net.dv8tion.jda.api.Permission.VIEW_CHANNEL);
    }

    private static long categoryOf(GuildChannel channel) {
        GuildChannel base = channel instanceof ThreadChannel thread ? thread.getParentChannel() : channel;
        return base instanceof ICategorizableChannel categorizable ? categorizable.getParentCategoryIdLong() : 0;
    }

    // ---- DiscordGateway: team notifications (phase 0) ----

    @Override
    public @NotNull MessageRef post(long channelId, @NotNull MessageSpec spec) throws GatewayException {
        TextChannel channel = channel(channelId);
        try {
            Message sent = channel.sendMessage(new MessageCreateBuilder()
                    .setContent(spec.content())
                    .setEmbeds(embed(spec))
                    .setComponents(components(spec))
                    // Only the staff role may be pinged, never @everyone or users.
                    .setAllowedMentions(EnumSet.of(Message.MentionType.ROLE))
                    .build()).complete();
            return new MessageRef(channelId, sent.getIdLong());
        } catch (RuntimeException e) {
            throw failure("post a message", e);
        }
    }

    @Override
    public void edit(@NotNull MessageRef message, @NotNull MessageSpec spec) throws GatewayException {
        TextChannel channel = channel(message.channelId());
        try {
            channel.editMessageById(message.messageId(), new MessageEditBuilder()
                    .setContent(spec.content() == null ? "" : spec.content())
                    .setEmbeds(embed(spec))
                    .setComponents(components(spec))
                    .build()).complete();
        } catch (RuntimeException e) {
            throw failure("edit a message", e);
        }
    }

    @Override
    public @NotNull Optional<MessageRef> find(long channelId, @NotNull String marker) throws GatewayException {
        TextChannel channel = channel(channelId);
        try {
            long self = this.jda.getSelfUser().getIdLong();
            return channel.getHistory().retrievePast(100).complete().stream()
                    .filter(message -> message.getAuthor().getIdLong() == self)
                    .filter(message -> message.getEmbeds().stream().anyMatch(embed ->
                            embed.getFooter() != null && embed.getFooter().getText() != null && embed.getFooter().getText().endsWith(marker)))
                    .findFirst()
                    .map(message -> new MessageRef(channelId, message.getIdLong()));
        } catch (RuntimeException e) {
            throw failure("read the channel history", e);
        }
    }

    // ---- DiscordGateway: tickets ----

    @Override
    public long selfId() {
        return this.jda.getSelfUser().getIdLong();
    }

    @Override
    public void ensureAppealPanel(long channelId, @NotNull String title, @NotNull String body, @NotNull String buttonLabel, @NotNull String marker)
            throws GatewayException {
        if (find(channelId, marker).isPresent()) {
            return;
        }
        TextChannel channel = channel(channelId);
        try {
            channel.sendMessage(new MessageCreateBuilder()
                    .setEmbeds(new EmbedBuilder().setTitle(title).setDescription(body).setFooter(marker).build())
                    .setComponents(ActionRow.of(Button.primary(APPEAL_BUTTON, buttonLabel)))
                    .setAllowedMentions(noMentions())
                    .build()).complete();
        } catch (RuntimeException e) {
            throw failure("post the appeal button", e);
        }
    }

    private Guild guild() throws GatewayException {
        JDA current = this.jda;
        Guild guild = current == null ? null : current.getGuildById(this.config.guildId());
        if (guild == null) {
            throw new GatewayException("The guild is not available", false, null);
        }
        return guild;
    }

    @Override
    public long createChannel(@NotNull NewChannel channel) throws GatewayException {
        Guild guild = guild();
        Category category = guild.getCategoryById(channel.categoryId());
        if (category == null) {
            throw new GatewayException("The ticket category is unknown or not visible to the bot", true, null);
        }
        try {
            var action = category.createTextChannel(channel.name()).setTopic(channel.topic())
                    // Nothing is public: @everyone cannot even see a ticket channel. There is deliberately no role overwrite.
                    .addRolePermissionOverride(guild.getPublicRole().getIdLong(), EnumSet.noneOf(net.dv8tion.jda.api.Permission.class),
                            EnumSet.of(net.dv8tion.jda.api.Permission.VIEW_CHANNEL));
            for (MemberAccess member : channel.members()) {
                action = action.addMemberPermissionOverride(member.userId(), jda(member.allow()), jda(member.deny()));
            }
            return action.complete().getIdLong();
        } catch (RuntimeException e) {
            throw failure("create a ticket channel", e);
        }
    }

    @Override
    public @NotNull Optional<Long> findChannelByTopic(@NotNull List<Long> categoryIds, @NotNull String marker) throws GatewayException {
        Guild guild = guild();
        for (long id : categoryIds) {
            Category category = guild.getCategoryById(id);
            if (category == null) {
                continue;
            }
            for (TextChannel channel : category.getTextChannels()) {
                if (channel.getTopic() != null && channel.getTopic().contains(marker)) {
                    return Optional.of(channel.getIdLong());
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean channelExists(long channelId) {
        JDA current = this.jda;
        return current != null && current.getGuildChannelById(channelId) != null;
    }

    @Override
    public void moveChannel(long channelId, long categoryId) throws GatewayException {
        TextChannel channel = channel(channelId);
        Category category = guild().getCategoryById(categoryId);
        if (category == null) {
            throw new GatewayException("The archive category is unknown or not visible to the bot", true, null);
        }
        try {
            if (channel.getParentCategoryIdLong() != categoryId) {
                channel.getManager().setParent(category).complete();
            }
        } catch (RuntimeException e) {
            throw failure("move a ticket channel", e);
        }
    }

    @Override
    public void deleteChannel(long channelId) throws GatewayException {
        TextChannel channel = channel(channelId);
        try {
            channel.delete().complete();
        } catch (RuntimeException e) {
            throw failure("delete a ticket channel", e);
        }
    }

    @Override
    public void setMemberAccess(long channelId, @NotNull MemberAccess access) throws GatewayException {
        TextChannel channel = channel(channelId);
        try {
            channel.getManager().putMemberPermissionOverride(access.userId(), jda(access.allow()), jda(access.deny())).complete();
        } catch (RuntimeException e) {
            throw failure("set channel access", e);
        }
    }

    @Override
    public void removeMemberAccess(long channelId, long userId) throws GatewayException {
        TextChannel channel = channel(channelId);
        try {
            channel.getManager().removePermissionOverride(userId).complete();
        } catch (RuntimeException e) {
            throw failure("remove channel access", e);
        }
    }

    @Override
    public @NotNull Set<Long> memberAccess(long channelId) throws GatewayException {
        TextChannel channel = channel(channelId);
        long self = this.jda.getSelfUser().getIdLong();
        return channel.getPermissionOverrides().stream().filter(PermissionOverride::isMemberOverride)
                .map(PermissionOverride::getIdLong).filter(id -> id != self).collect(Collectors.toSet());
    }

    @Override
    public boolean hasRole(long userId, long roleId) throws GatewayException {
        try {
            Member member = guild().retrieveMemberById(userId).complete();
            return member.getRoles().stream().anyMatch(role -> role.getIdLong() == roleId);
        } catch (ErrorResponseException e) {
            if (e.getErrorResponse() == ErrorResponse.UNKNOWN_MEMBER || e.getErrorResponse() == ErrorResponse.UNKNOWN_USER) {
                return false;
            }
            throw failure("look up a member", e);
        } catch (RuntimeException e) {
            throw failure("look up a member", e);
        }
    }

    @Override
    public @NotNull MessageRef postRelay(long channelId, @NotNull String label, @NotNull String text, @NotNull String ref) throws GatewayException {
        GuildMessageChannel channel = messageChannel(channelId);
        try {
            Message sent = channel.sendMessageEmbeds(new EmbedBuilder().setAuthor(label).setDescription(cut(text)).setFooter(ref).build())
                    .setAllowedMentions(noMentions()).complete();
            return new MessageRef(channelId, sent.getIdLong());
        } catch (RuntimeException e) {
            throw failure("relay a message", e);
        }
    }

    @Override
    public void editRelay(@NotNull MessageRef message, @NotNull String text) throws GatewayException {
        GuildMessageChannel channel = messageChannel(message.channelId());
        try {
            Message existing = channel.retrieveMessageById(message.messageId()).complete();
            if (existing.getEmbeds().isEmpty()) {
                return;
            }
            channel.editMessageEmbedsById(message.messageId(), new EmbedBuilder(existing.getEmbeds().getFirst()).setDescription(cut(text)).build()).complete();
        } catch (ErrorResponseException e) {
            if (e.getErrorResponse() != ErrorResponse.UNKNOWN_MESSAGE) {
                throw failure("edit a relayed message", e);
            }
        } catch (RuntimeException e) {
            throw failure("edit a relayed message", e);
        }
    }

    @Override
    public void deleteMessage(@NotNull MessageRef message) throws GatewayException {
        GuildMessageChannel channel = messageChannel(message.channelId());
        try {
            channel.deleteMessageById(message.messageId()).complete();
        } catch (ErrorResponseException e) {
            if (e.getErrorResponse() != ErrorResponse.UNKNOWN_MESSAGE) {
                throw failure("delete a message", e);
            }
        } catch (RuntimeException e) {
            throw failure("delete a message", e);
        }
    }

    @Override
    public void notice(long channelId, @NotNull String text) throws GatewayException {
        GuildMessageChannel channel = messageChannel(channelId);
        try {
            channel.sendMessage(text).setAllowedMentions(noMentions()).complete();
        } catch (RuntimeException e) {
            throw failure("post a notice", e);
        }
    }

    /** No user, role or everyone mention is ever resolved in a message the bot relays. */
    static EnumSet<Message.MentionType> noMentions() {
        return EnumSet.noneOf(Message.MentionType.class);
    }

    @Override
    public @NotNull MessageRef postPlain(long channelId, @NotNull String text) throws GatewayException {
        GuildMessageChannel channel = messageChannel(channelId);
        try {
            Message sent = channel.sendMessage(text).setAllowedMentions(noMentions()).complete();
            return new MessageRef(channelId, sent.getIdLong());
        } catch (RuntimeException e) {
            throw failure("post a message", e);
        }
    }

    @Override
    public @NotNull MessageRef postFile(long channelId, String text, @NotNull String fileName, byte @NotNull [] data) throws GatewayException {
        GuildMessageChannel channel = messageChannel(channelId);
        try {
            var action = channel.sendFiles(net.dv8tion.jda.api.utils.FileUpload.fromData(data, fileName)).setAllowedMentions(noMentions());
            Message sent = (text == null ? action : action.setContent(text)).complete();
            return new MessageRef(channelId, sent.getIdLong());
        } catch (RuntimeException e) {
            throw failure("upload a file", e);
        }
    }

    @Override
    public void react(@NotNull MessageRef message, @NotNull String emoji) throws GatewayException {
        GuildMessageChannel channel = messageChannel(message.channelId());
        try {
            channel.addReactionById(message.messageId(), net.dv8tion.jda.api.entities.emoji.Emoji.fromUnicode(emoji)).complete();
        } catch (ErrorResponseException e) {
            if (e.getErrorResponse() != ErrorResponse.UNKNOWN_MESSAGE) {
                throw failure("add a reaction", e);
            }
        } catch (RuntimeException e) {
            throw failure("add a reaction", e);
        }
    }

    @Override
    public @NotNull List<IncomingMessage> history(long channelId, long afterId, int limit) throws GatewayException {
        GuildMessageChannel channel = messageChannel(channelId);
        try {
            var action = afterId == 0 ? channel.getHistoryFromBeginning(limit) : channel.getHistoryAfter(afterId, limit);
            List<Message> messages = new ArrayList<>(action.complete().getRetrievedHistory());
            messages.sort(Comparator.comparingLong(Message::getIdLong));
            Map<Long, Boolean> roles = new HashMap<>();
            List<IncomingMessage> result = new ArrayList<>();
            for (Message message : messages) {
                if (!supported(message)) {
                    continue;
                }
                boolean staff = false;
                if (!message.getAuthor().isBot()) {
                    long author = message.getAuthor().getIdLong();
                    Boolean cached = roles.get(author);
                    if (cached == null) {
                        cached = hasRole(author, this.config.staffRoleId());
                        roles.put(author, cached);
                    }
                    staff = cached;
                }
                result.add(incoming(message, staff));
            }
            return result;
        } catch (RuntimeException e) {
            throw failure("read the channel history", e);
        }
    }

    @Override
    public @NotNull Optional<Instant> lastActivity(long channelId) throws GatewayException {
        GuildMessageChannel channel = messageChannel(channelId);
        long latest = channel.getLatestMessageIdLong();
        return latest == 0 ? Optional.empty() : Optional.of(TimeUtil.getTimeCreated(latest).toInstant());
    }

    @Override
    public @NotNull Set<Permission> missingPermissions(long categoryId) {
        JDA current = this.jda;
        Guild guild = current == null ? null : current.getGuildById(this.config.guildId());
        GuildChannel channel = guild == null ? null : guild.getGuildChannelById(categoryId);
        Set<Permission> missing = EnumSet.noneOf(Permission.class);
        for (Permission permission : Permission.values()) {
            if (permission == Permission.ATTACH_FILES || permission == Permission.MANAGE_MESSAGES) {
                continue;
            }
            if (channel == null || !guild.getSelfMember().hasPermission(channel, jda(permission))) {
                missing.add(permission);
            }
        }
        return missing;
    }

    @Override
    public boolean roleCanView(long categoryId, long roleId) {
        JDA current = this.jda;
        Guild guild = current == null ? null : current.getGuildById(this.config.guildId());
        GuildChannel channel = guild == null ? null : guild.getGuildChannelById(categoryId);
        Role role = guild == null ? null : guild.getRoleById(roleId);
        return channel != null && role != null && role.hasPermission(channel, net.dv8tion.jda.api.Permission.VIEW_CHANNEL);
    }

    // ---- helpers ----

    private static String cut(String text) {
        return text.length() <= MAX_EMBED_TEXT ? text : text.substring(0, MAX_EMBED_TEXT);
    }

    private TextChannel channel(long channelId) throws GatewayException {
        JDA current = this.jda;
        TextChannel channel = current == null ? null : current.getTextChannelById(channelId);
        if (channel == null) {
            throw new GatewayException("Channel " + channelId + " is unknown or not visible to the bot", true, null);
        }
        return channel;
    }

    private GuildMessageChannel messageChannel(long channelId) throws GatewayException {
        JDA current = this.jda;
        GuildMessageChannel channel = current == null ? null : current.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) {
            throw new GatewayException("Channel " + channelId + " is unknown or not visible to the bot", true, null);
        }
        return channel;
    }

    private static MessageEmbed embed(MessageSpec spec) {
        EmbedBuilder builder = new EmbedBuilder().setTitle(spec.title()).setColor(spec.color())
                .setFooter(spec.footer()).setTimestamp(spec.timestamp());
        spec.fields().forEach(field -> builder.addField(field.name(), field.value(), field.inline()));
        return builder.build();
    }

    private static Collection<ActionRow> components(MessageSpec spec) {
        if (!spec.buttons().isEmpty()) {
            // Discord allows five buttons in a row and five rows in a message.
            List<Button> buttons = spec.buttons().stream().map(JdaGateway::button).toList();
            List<ActionRow> rows = new ArrayList<>();
            for (int from = 0; from < buttons.size(); from += 5) {
                rows.add(ActionRow.of(buttons.subList(from, Math.min(from + 5, buttons.size()))));
            }
            return rows;
        }
        if (spec.linkUrl() == null || spec.linkLabel() == null) {
            return List.of();
        }
        return List.of(ActionRow.of(Button.link(spec.linkUrl(), spec.linkLabel())));
    }

    private static Button button(ControlButton button) {
        if (button.url() != null) {
            return Button.link(button.url(), button.label());
        }
        ButtonStyle style = switch (button.style()) {
            case PRIMARY -> ButtonStyle.PRIMARY;
            case SECONDARY -> ButtonStyle.SECONDARY;
            case SUCCESS -> ButtonStyle.SUCCESS;
            case DANGER -> ButtonStyle.DANGER;
            case LINK -> ButtonStyle.LINK;
        };
        return Button.of(style, button.id(), button.label()).withDisabled(button.disabled());
    }

    private static GatewayException failure(String what, RuntimeException e) {
        boolean permanent = e instanceof ErrorResponseException response
                && (response.getErrorResponse() == ErrorResponse.MISSING_ACCESS
                || response.getErrorResponse() == ErrorResponse.MISSING_PERMISSIONS
                || response.getErrorResponse() == ErrorResponse.UNKNOWN_CHANNEL);
        return new GatewayException("Could not " + what + ": " + e.getClass().getSimpleName(), permanent, e);
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
