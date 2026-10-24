package net.onelitefeather.blackhole.discord.testing;

import org.jetbrains.annotations.NotNull;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * An in-memory Discord guild behind {@link net.onelitefeather.blackhole.discord.gateway.DiscordGateway}: channels with
 * member overwrites and messages, a listener the test drives by acting as a user, and failures it can be told to produce.
 * Each test builds its own.
 */
public final class FakeGuild extends UnsupportedDiscord {

    public static final long SELF = 900L;

    /** A message in a channel. */
    public static final class Msg {
        public final long id;
        public final long channelId;
        public final long authorId;
        public final boolean bot;
        public String text;
        public final Instant createdAt;
        public Instant editedAt;
        public final String footer;
        public final String label;

        Msg(long id, long channelId, long authorId, boolean bot, String text, Instant createdAt, String footer, String label) {
            this.id = id;
            this.channelId = channelId;
            this.authorId = authorId;
            this.bot = bot;
            this.text = text;
            this.createdAt = createdAt;
            this.footer = footer;
            this.label = label;
        }
    }

    /** A channel. */
    public static final class Chan {
        public final long id;
        public String name;
        public String topic;
        public long category;
        public final Map<Long, MemberAccess> members = new LinkedHashMap<>();
        public final List<Msg> messages = new ArrayList<>();

        Chan(long id, String name, String topic, long category) {
            this.id = id;
            this.name = name;
            this.topic = topic;
            this.category = category;
        }
    }

    private final Clock clock;
    private final long staffRole;
    public final Map<Long, Chan> channels = new LinkedHashMap<>();
    public final Map<Long, Set<Long>> roleMembers = new HashMap<>();
    public final Set<Long> bots = new HashSet<>();
    public final Map<String, GatewayException> failOn = new HashMap<>();
    public final Map<Long, Set<Permission>> missing = new HashMap<>();
    public final Set<Long> categoriesStaffRoleCanView = new HashSet<>();
    public final List<String> calls = new ArrayList<>();
    public final List<String> panels = new ArrayList<>();
    public boolean connected = true;
    private Listener listener;
    private long nextId = 1000;

    public FakeGuild(@NotNull Clock clock, long staffRole) {
        this.clock = clock;
        this.staffRole = staffRole;
    }

    private void check(String operation) throws GatewayException {
        this.calls.add(operation);
        GatewayException failure = this.failOn.get(operation);
        if (failure != null) {
            throw failure;
        }
    }

    /** A channel that already exists, such as the appeal channel or a public channel. */
    public Chan addChannel(long id, String name, long category) {
        Chan chan = new Chan(id, name, null, category);
        this.channels.put(id, chan);
        return chan;
    }

    public Set<Long> memberAccessForTest(long channelId) {
        return this.channels.get(channelId).members.keySet().stream().filter(id -> id != SELF).collect(Collectors.toSet());
    }

    public Listener listener() {
        return this.listener;
    }

    public Chan channel(long id) {
        return this.channels.get(id);
    }

    public List<Msg> messages(long channelId) {
        return this.channels.get(channelId).messages;
    }

    public List<String> texts(long channelId) {
        return messages(channelId).stream().map(m -> m.text).toList();
    }

    public void giveRole(long user, long role) {
        this.roleMembers.computeIfAbsent(role, ignored -> new HashSet<>()).add(user);
    }

    public Optional<Chan> channelByTopic(String marker) {
        return this.channels.values().stream().filter(c -> c.topic != null && c.topic.contains(marker)).findFirst();
    }

    // ---- acting as users ----

    /** A user writes a message; the listener hears of it. */
    public long say(long channelId, long author, String text) {
        Msg msg = addMessage(channelId, author, text, null, null);
        this.listener.onMessage(incoming(msg));
        return msg.id;
    }

    /** A user writes a message while the bot is not listening (it was down). */
    public long sayWhileBotDown(long channelId, long author, String text) {
        return addMessage(channelId, author, text, null, null).id;
    }

    public void userEdits(long channelId, long messageId, String text) {
        Msg msg = find(channelId, messageId);
        msg.text = text;
        msg.editedAt = this.clock.instant();
        this.listener.onMessageEdit(incoming(msg));
    }

    public void userEditsWhileBotDown(long channelId, long messageId, String text) {
        Msg msg = find(channelId, messageId);
        msg.text = text;
        msg.editedAt = this.clock.instant();
    }

    public void userDeletes(long channelId, long messageId) {
        messages(channelId).removeIf(m -> m.id == messageId);
        this.listener.onMessageDelete(channelId, messageId);
    }

    public void userDeletesWhileBotDown(long channelId, long messageId) {
        messages(channelId).removeIf(m -> m.id == messageId);
    }

    public void userDeletesChannel(long channelId) {
        this.channels.remove(channelId);
        this.listener.onChannelDelete(channelId);
    }

    private Msg find(long channelId, long messageId) {
        return messages(channelId).stream().filter(m -> m.id == messageId).findFirst().orElseThrow();
    }

    private Msg addMessage(long channelId, long author, String text, String footer, String label) {
        Msg msg = new Msg(this.nextId++, channelId, author, this.bots.contains(author), text, this.clock.instant(), footer, label);
        this.channels.get(channelId).messages.add(msg);
        return msg;
    }

    private IncomingMessage incoming(Msg m) {
        Chan chan = this.channels.get(m.channelId);
        boolean self = m.authorId == SELF;
        return new IncomingMessage(m.id, m.channelId, chan.category, m.authorId, "user-" + m.authorId, m.bot, self,
                this.roleMembers.getOrDefault(this.staffRole, Set.of()).contains(m.authorId), m.createdAt, m.editedAt, m.text, List.of(), List.of(),
                self ? m.footer : null);
    }

    // ---- DiscordGateway ----

    @Override
    public void listener(@NotNull Listener listener) {
        this.listener = listener;
    }

    @Override
    public long selfId() {
        return SELF;
    }

    @Override
    public void start() {
    }

    @Override
    public boolean connected() {
        return this.connected;
    }

    @Override
    public void close() {
    }

    @Override
    public @NotNull MessageRef post(long channelId, @NotNull MessageSpec spec) throws GatewayException {
        check("post");
        return new MessageRef(channelId, addMessage(channelId, SELF, spec.title(), spec.footer(), null).id);
    }

    @Override
    public void edit(@NotNull MessageRef message, @NotNull MessageSpec spec) throws GatewayException {
        check("edit");
        find(message.channelId(), message.messageId()).text = spec.title();
    }

    @Override
    public @NotNull Optional<MessageRef> find(long channelId, @NotNull String marker) throws GatewayException {
        check("find");
        Chan chan = this.channels.get(channelId);
        if (chan == null) {
            throw new GatewayException("unknown channel", true, null);
        }
        return chan.messages.stream().filter(m -> m.authorId == SELF && m.footer != null && m.footer.endsWith(marker))
                .map(m -> new MessageRef(channelId, m.id)).findFirst();
    }

    @Override
    public void ensureAppealPanel(long channelId, @NotNull String title, @NotNull String body, @NotNull String buttonLabel, @NotNull String marker)
            throws GatewayException {
        check("ensureAppealPanel");
        if (find(channelId, marker).isEmpty()) {
            addMessage(channelId, SELF, title, marker, null);
            this.panels.add(marker);
        }
    }

    @Override
    public long createChannel(@NotNull NewChannel channel) throws GatewayException {
        check("createChannel");
        Chan chan = new Chan(this.nextId++, channel.name(), channel.topic(), channel.categoryId());
        channel.members().forEach(member -> chan.members.put(member.userId(), member));
        this.channels.put(chan.id, chan);
        return chan.id;
    }

    @Override
    public @NotNull Optional<Long> findChannelByTopic(@NotNull List<Long> categoryIds, @NotNull String marker) throws GatewayException {
        check("findChannelByTopic");
        return this.channels.values().stream().filter(c -> categoryIds.contains(c.category) && c.topic != null && c.topic.contains(marker))
                .map(c -> c.id).findFirst();
    }

    @Override
    public boolean channelExists(long channelId) {
        return this.channels.containsKey(channelId);
    }

    @Override
    public void moveChannel(long channelId, long categoryId) throws GatewayException {
        check("moveChannel");
        this.channels.get(channelId).category = categoryId;
    }

    @Override
    public void deleteChannel(long channelId) throws GatewayException {
        check("deleteChannel");
        this.channels.remove(channelId);
        // Discord tells the bot about its own delete, too.
        this.listener.onChannelDelete(channelId);
    }

    @Override
    public void setMemberAccess(long channelId, @NotNull MemberAccess access) throws GatewayException {
        check("setMemberAccess");
        this.channels.get(channelId).members.put(access.userId(), access);
    }

    @Override
    public void removeMemberAccess(long channelId, long userId) throws GatewayException {
        check("removeMemberAccess");
        this.channels.get(channelId).members.remove(userId);
    }

    @Override
    public @NotNull Set<Long> memberAccess(long channelId) throws GatewayException {
        check("memberAccess");
        return this.channels.get(channelId).members.keySet().stream().filter(id -> id != SELF).collect(Collectors.toSet());
    }

    @Override
    public boolean hasRole(long userId, long roleId) throws GatewayException {
        check("hasRole");
        return this.roleMembers.getOrDefault(roleId, Set.of()).contains(userId);
    }

    @Override
    public @NotNull MessageRef postRelay(long channelId, @NotNull String label, @NotNull String text, @NotNull String ref) throws GatewayException {
        check("postRelay");
        return new MessageRef(channelId, addMessage(channelId, SELF, text, ref, label).id);
    }

    @Override
    public void editRelay(@NotNull MessageRef message, @NotNull String text) throws GatewayException {
        check("editRelay");
        messages(message.channelId()).stream().filter(m -> m.id == message.messageId()).forEach(m -> m.text = text);
    }

    @Override
    public void deleteMessage(@NotNull MessageRef message) throws GatewayException {
        check("deleteMessage");
        Chan chan = this.channels.get(message.channelId());
        if (chan != null) {
            chan.messages.removeIf(m -> m.id == message.messageId());
        }
    }

    @Override
    public void notice(long channelId, @NotNull String text) throws GatewayException {
        check("notice");
        addMessage(channelId, SELF, text, null, null);
    }

    @Override
    public @NotNull List<IncomingMessage> history(long channelId, long afterId, int limit) throws GatewayException {
        check("history");
        Chan chan = this.channels.get(channelId);
        if (chan == null) {
            throw new GatewayException("unknown channel", true, null);
        }
        return chan.messages.stream().filter(m -> m.id > afterId).sorted((a, b) -> Long.compare(a.id, b.id)).limit(limit).map(this::incoming).toList();
    }

    @Override
    public @NotNull Optional<Instant> lastActivity(long channelId) throws GatewayException {
        check("lastActivity");
        Chan chan = this.channels.get(channelId);
        if (chan == null || chan.messages.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(chan.messages.getLast().createdAt);
    }

    @Override
    public @NotNull Set<Permission> missingPermissions(long categoryId) {
        return this.missing.getOrDefault(categoryId, Set.of());
    }

    @Override
    public boolean roleCanView(long categoryId, long roleId) {
        return this.categoriesStaffRoleCanView.contains(categoryId);
    }
}
