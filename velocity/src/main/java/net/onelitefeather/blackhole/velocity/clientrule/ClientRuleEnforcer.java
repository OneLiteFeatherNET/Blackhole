package net.onelitefeather.blackhole.velocity.clientrule;

import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.ClientRuleAction;
import net.onelitefeather.blackhole.client.model.ClientRuleHitCreateRequest;
import net.onelitefeather.blackhole.client.model.ClientRuleKind;
import net.onelitefeather.blackhole.velocity.component.Permissions;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Turns rule hits into what the player and the team see: a refusal (login, connect, or a disconnect for
 * a trait that arrived late), a hit report to the backend and the staff notice.
 *
 * <ul>
 *     <li>{@code PROTOCOL} rules are checked at login (global) or when connecting to a covered server (scoped).</li>
 *     <li>{@code BRAND}, {@code MOD} and {@code CHANNEL} rules are checked whenever the trait arrives and at each
 *     connect; they never refuse a login, because those traits are not reliably known then. A {@code DENY} on a
 *     trait refuses as soon as the trait is known: at the first connect if it is known by then, otherwise the player
 *     is disconnected when it arrives.</li>
 *     <li>Staff with {@value Permissions#CLIENT_RULES_BYPASS} are never refused or punished; their hits are recorded
 *     as bypassed.</li>
 *     <li>Everything fails open: no rules loaded, an unknown server or a backend that does not answer enforces
 *     nothing it cannot decide, and never blocks a player.</li>
 * </ul>
 *
 * <p>A hit is reported and announced once per player, rule and session; a refusal is repeated each time it applies.</p>
 */
public final class ClientRuleEnforcer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientRuleEnforcer.class);

    private static final Set<ClientRuleKind> LOGIN_KINDS = Set.of(ClientRuleKind.PROTOCOL);
    private static final Set<ClientRuleKind> CONNECT_KINDS = Set.of(ClientRuleKind.PROTOCOL, ClientRuleKind.BRAND, ClientRuleKind.MOD, ClientRuleKind.CHANNEL);
    private static final Set<ClientRuleKind> TRAIT_KINDS = Set.of(ClientRuleKind.BRAND, ClientRuleKind.MOD, ClientRuleKind.CHANNEL);

    private final TraitCollector traits;
    private final RuleRegistry registry;
    private final RuleEngine engine;
    private final ClientRuleBackend backend;
    private final ClientRuleNotifier notifier;
    private final Executor async;
    private final Map<String, Set<String>> reported = new ConcurrentHashMap<>();

    /**
     * @param async runs the backend call and the announcement off the event thread
     */
    public ClientRuleEnforcer(@NotNull TraitCollector traits, @NotNull RuleRegistry registry, @NotNull RuleEngine engine,
                              @NotNull ClientRuleBackend backend, @NotNull ClientRuleNotifier notifier, @NotNull Executor async) {
        this.traits = traits;
        this.registry = registry;
        this.engine = engine;
        this.backend = backend;
        this.notifier = notifier;
        this.async = async;
    }

    /** Refuses the login when a global {@code PROTOCOL} rule with {@code DENY} hits. */
    public void onLogin(@NotNull LoginEvent event) {
        Player player = event.getPlayer();
        String owner = UUIDConverter.convertToSHA(player.getUniqueId());
        ClientTraits known = this.traits.loggedIn(owner, player.getProtocolVersion().getProtocol());
        try {
            Optional<ClientRule> refusal = handle(player, owner, null, this.engine.hits(known, null, LOGIN_KINDS, this.registry.rules()));
            refusal.ifPresent(rule -> event.setResult(ResultedEvent.ComponentResult.denied(DenyMessages.of(rule))));
        } catch (RuntimeException e) {
            LOGGER.warn("Client rules could not be checked at login, allowing it: {}", e.getMessage());
        }
    }

    /**
     * Refuses the connect to a server a {@code DENY} rule covers. The player stays where they are; without
     * a current server (the first connect) they are disconnected, as there is nowhere to stay.
     */
    public void onPreConnect(@NotNull ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        String owner = UUIDConverter.convertToSHA(player.getUniqueId());
        String server = event.getOriginalServer().getServerInfo().getName();
        try {
            ClientTraits known = this.traits.traits(owner).orElse(ClientTraits.unknown().withProtocol(player.getProtocolVersion().getProtocol()));
            Optional<ClientRule> refusal = handle(player, owner, server, this.engine.hits(known, server, CONNECT_KINDS, this.registry.rules()));
            if (refusal.isEmpty()) {
                return;
            }
            Component message = DenyMessages.of(refusal.get());
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            if (player.getCurrentServer().isPresent()) {
                player.sendMessage(message);
            } else {
                player.disconnect(message);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Client rules could not be checked at connect, allowing it: {}", e.getMessage());
        }
    }

    /** A trait (brand, channels, mods) arrived: checks the trait rules for the server the player is on and disconnects on a {@code DENY}. */
    public void onTrait(@NotNull Player player) {
        String owner = UUIDConverter.convertToSHA(player.getUniqueId());
        try {
            ClientTraits known = this.traits.traits(owner).orElse(null);
            if (known == null) {
                return;
            }
            String server = player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse(null);
            Optional<ClientRule> refusal = handle(player, owner, server, this.engine.hits(known, server, TRAIT_KINDS, this.registry.rules()));
            refusal.ifPresent(rule -> player.disconnect(DenyMessages.of(rule)));
        } catch (RuntimeException e) {
            LOGGER.warn("Client rules could not be checked for a new trait, allowing it: {}", e.getMessage());
        }
    }

    /** Forgets the session's dedupe state of a player who left. */
    public void onDisconnect(@NotNull String owner) {
        this.reported.remove(owner);
    }

    /**
     * Reports and announces each new hit and returns the first rule that refuses the player.
     *
     * @return the first {@code DENY} rule that applies to a player without the bypass permission
     */
    private Optional<ClientRule> handle(Player player, String owner, @Nullable String server, List<ClientRule> hits) {
        if (hits.isEmpty()) {
            return Optional.empty();
        }
        boolean bypassed = player.hasPermission(Permissions.CLIENT_RULES_BYPASS);
        ClientRule refusal = null;
        for (ClientRule rule : hits) {
            boolean enforced = rule.action() == ClientRuleAction.DENY && !bypassed;
            if (enforced && refusal == null) {
                refusal = rule;
            }
            if (this.reported.computeIfAbsent(owner, key -> ConcurrentHashMap.newKeySet()).add(rule.key())) {
                report(player, owner, server, rule, enforced, bypassed);
            }
        }
        return Optional.ofNullable(refusal);
    }

    private void report(Player player, String owner, @Nullable String server, ClientRule rule, boolean enforced, boolean bypassed) {
        String name = player.getUsername();
        this.async.execute(() -> {
            try {
                this.backend.reportHit(new ClientRuleHitCreateRequest().rule(rule.key()).owner(owner).server(server).enforced(enforced).bypassed(bypassed));
            } catch (ApiException | RuntimeException e) {
                // Fail open: the hit is not recorded and a TEMPLATE applies nothing; the notice below still happens.
                LOGGER.debug("Client rule hit {} not recorded: {}", rule.key(), e.getMessage());
            }
            try {
                this.notifier.announce(new ClientRuleNotice(rule.displayName(), name, rule.kind().getValue(), rule.action().getValue(), enforced));
            } catch (RuntimeException e) {
                LOGGER.debug("Client rule notice {} not delivered: {}", rule.key(), e.getMessage());
            }
        });
    }
}
