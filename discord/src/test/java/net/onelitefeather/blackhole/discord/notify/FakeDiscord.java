package net.onelitefeather.blackhole.discord.notify;

import net.onelitefeather.blackhole.discord.gateway.DiscordGateway;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** An in-memory Discord: channels hold the messages the bot posted. Each test builds its own. */
final class FakeDiscord implements DiscordGateway {

    final Map<MessageRef, MessageSpec> messages = new LinkedHashMap<>();
    final List<String> calls = new ArrayList<>();
    GatewayException failure;
    private long nextId = 1;

    @Override
    public void start() {
    }

    @Override
    public boolean connected() {
        return true;
    }

    @Override
    public @NotNull MessageRef post(long channelId, @NotNull MessageSpec spec) throws GatewayException {
        this.calls.add("post");
        fail();
        MessageRef ref = new MessageRef(channelId, this.nextId++);
        this.messages.put(ref, spec);
        return ref;
    }

    @Override
    public void edit(@NotNull MessageRef message, @NotNull MessageSpec spec) throws GatewayException {
        this.calls.add("edit");
        fail();
        this.messages.put(message, spec);
    }

    @Override
    public @NotNull Optional<MessageRef> find(long channelId, @NotNull String marker) throws GatewayException {
        this.calls.add("find");
        fail();
        return this.messages.entrySet().stream()
                .filter(entry -> entry.getKey().channelId() == channelId && entry.getValue().footer().endsWith(marker))
                .map(Map.Entry::getKey).findFirst();
    }

    private void fail() throws GatewayException {
        if (this.failure != null) {
            throw this.failure;
        }
    }

    @Override
    public void close() {
    }
}
