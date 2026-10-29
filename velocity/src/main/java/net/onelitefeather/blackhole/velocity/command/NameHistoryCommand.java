package net.onelitefeather.blackhole.velocity.command;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.NameHistoryName;
import net.onelitefeather.blackhole.client.model.NameHistoryOwner;
import net.onelitefeather.blackhole.client.model.NameHistoryPage;
import net.onelitefeather.blackhole.velocity.component.Fmt;
import net.onelitefeather.blackhole.velocity.component.Mode;
import net.onelitefeather.blackhole.velocity.config.BlackholeConfig;
import net.onelitefeather.blackhole.velocity.punish.BackendGateway;
import net.onelitefeather.blackhole.velocity.punish.TargetNotFoundException;
import net.onelitefeather.blackhole.velocity.punish.TargetResolver;
import net.onelitefeather.blackhole.velocity.punish.TargetResolver.ResolvedTarget;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code /blackhole <user> names}: the names a player used, newest first. The player is resolved online
 * first, then live, then through the old-name route; an old name that several accounts used lists them
 * and picks none.
 */
public final class NameHistoryCommand {

    /** The most names one listing shows; the backend is asked for no more. */
    static final int MAX_NAMES = 20;

    private static final Logger LOGGER = LoggerFactory.getLogger(NameHistoryCommand.class);

    /** The account a listing is about: its hashed owner, raw UUID and the name to show. */
    private record Subject(String ownerHash, UUID uuid, String name) {
    }

    private final BackendGateway backend;
    private final TargetResolver resolver;
    private final ZoneId zone;

    @Inject
    public NameHistoryCommand(@NotNull BackendGateway backend, @NotNull ProxyServer proxy, @NotNull BlackholeConfig config) {
        this(backend, proxy::getPlayer, config.getZone());
    }

    NameHistoryCommand(@NotNull BackendGateway backend, @NotNull TargetResolver.OnlineLookup online, @NotNull ZoneId zone) {
        this.backend = backend;
        this.resolver = new TargetResolver(online, backend::resolvePlayer);
        this.zone = zone;
    }

    @Command("blackhole <user> names")
    @Permission("blackhole.user.info")
    @CommandDescription("List the names a player has used, newest first")
    public void names(
            @NotNull CommandContext<CommandSource> context,
            @Argument(value = "user", suggestions = "players") @NotNull String user
    ) {
        list(context.sender(), user);
    }

    void list(CommandSource sender, String input) {
        Mode mode = Mode.of(sender);
        Subject subject;
        try {
            subject = resolve(sender, input);
        } catch (ApiException e) {
            LOGGER.error("Failed to look up the former name {}: {}", input, e.getMessage());
            sender.sendMessage(Component.translatable("names.unavailable"));
            return;
        }
        if (subject == null) {
            return;
        }
        NameHistoryPage page;
        try {
            page = this.backend.nameHistory(subject.ownerHash(), MAX_NAMES);
        } catch (ApiException e) {
            LOGGER.error("Failed to read the name history of {}: {}", subject.name(), e.getMessage());
            sender.sendMessage(Component.translatable("names.unavailable"));
            return;
        }
        sender.sendMessage(render(subject, page, mode));
    }

    /**
     * @return the account the input names, or {@code null} after telling the sender why there is none
     * @throws ApiException if the old-name route could not be asked
     */
    private Subject resolve(CommandSource sender, String input) throws ApiException {
        try {
            ResolvedTarget target = this.resolver.resolve(input);
            return new Subject(target.ownerHash(), target.uuid(), target.name());
        } catch (TargetNotFoundException unknown) {
            // not a name the live lookup knows: it may be a name an account used before
        }
        List<NameHistoryOwner> owners = this.backend.nameOwners(input);
        if (owners.isEmpty()) {
            sender.sendMessage(Component.translatable("names.not_found", Component.text(input)));
            return null;
        }
        if (owners.size() > 1) {
            sender.sendMessage(ambiguous(input, owners));
            return null;
        }
        NameHistoryOwner owner = owners.getFirst();
        return new Subject(owner.getOwner(), UUID.fromString(owner.getPlayerUuid()), owner.getCurrentName());
    }

    private Component render(Subject subject, NameHistoryPage page, Mode mode) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("names.header", Fmt.player(subject.name(), mode), Component.text(subject.uuid().toString())));
        List<NameHistoryName> names = page.getNames() == null ? List.of() : page.getNames().stream().limit(MAX_NAMES).toList();
        if (names.isEmpty()) {
            lines.add(Component.translatable("names.none"));
        }
        for (NameHistoryName name : names) {
            lines.add(Component.translatable("names.entry", Component.text(name.getName()),
                    Fmt.date(Instant.ofEpochMilli(name.getFirstSeen()), this.zone),
                    Fmt.date(Instant.ofEpochMilli(name.getLastSeen()), this.zone)));
        }
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    private Component ambiguous(String input, List<NameHistoryOwner> owners) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("names.ambiguous.header", Component.text(input)));
        for (NameHistoryOwner owner : owners) {
            lines.add(Component.translatable("names.ambiguous.entry", Component.text(owner.getCurrentName()),
                    Component.text(owner.getPlayerUuid()), Fmt.date(Instant.ofEpochMilli(owner.getLastSeen()), this.zone)));
        }
        return Component.join(JoinConfiguration.newlines(), lines);
    }
}
