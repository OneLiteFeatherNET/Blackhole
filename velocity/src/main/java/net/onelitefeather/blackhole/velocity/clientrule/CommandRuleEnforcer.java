package net.onelitefeather.blackhole.velocity.clientrule;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.proxy.Player;
import net.onelitefeather.blackhole.client.model.ClientRuleAction;
import net.onelitefeather.blackhole.client.model.ClientRuleKind;
import net.onelitefeather.blackhole.velocity.component.Permissions;
import net.onelitefeather.blackhole.velocity.utils.UUIDConverter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies the {@code COMMAND} rules to what players type, for the server they are on (global rules need
 * none). A denied command is neither run on the proxy nor forwarded to the backend server, and the player
 * sees the rule's English message. A command carrying signed arguments is denied only when
 * {@link CommandDenial} says it is safe; otherwise it is delivered, the player is still told, and the hit is
 * recorded as not enforced. Staff with {@value Permissions#CLIENT_RULES_BYPASS} are exempt (the hit is
 * recorded as bypassed). A rule hit is reported and announced at most once per {@value #DEDUPE_SECONDS}
 * seconds for a player and rule, so a held key does not flood the table or the staff. Fails open.
 *
 * <p>Hiding is cosmetic (tab completion): the literal nodes of the rules' commands are removed from the
 * command tree sent to players on covered servers. It never replaces the denial.</p>
 */
public final class CommandRuleEnforcer {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommandRuleEnforcer.class);

    static final long DEDUPE_SECONDS = 60;
    private static final Duration DEDUPE = Duration.ofSeconds(DEDUPE_SECONDS);

    private final RuleRegistry registry;
    private final RuleEngine engine;
    private final HitReporter reporter;
    private final CommandDenial denial;
    private final Clock clock;
    private final Map<String, Map<String, Instant>> reported = new ConcurrentHashMap<>();

    public CommandRuleEnforcer(@NotNull RuleRegistry registry, @NotNull RuleEngine engine, @NotNull HitReporter reporter,
                               @NotNull CommandDenial denial, @NotNull Clock clock) {
        this.registry = registry;
        this.engine = engine;
        this.reporter = reporter;
        this.denial = denial;
        this.clock = clock;
    }

    /** Checks a command a player typed (or clicked) and denies it when a {@code DENY} rule hits and that is safe. */
    public void onCommand(@NotNull Player player, @NotNull CommandExecuteEvent event) {
        try {
            CommandExecuteEvent.InvocationInfo info = event.getInvocationInfo();
            if (info == null || info.source() != CommandExecuteEvent.Source.PLAYER) {
                return;
            }
            Set<String> words = CommandWords.candidates(event.getCommand());
            if (words.isEmpty()) {
                return;
            }
            String server = serverOf(player);
            List<ClientRule> hits = this.engine.commandHits(words, server, this.registry.rules());
            if (hits.isEmpty()) {
                return;
            }
            boolean bypassed = player.hasPermission(Permissions.CLIENT_RULES_BYPASS);
            boolean mayDeny = this.denial.mayDeny(info.signedState());
            String owner = UUIDConverter.convertToSHA(player.getUniqueId());
            ClientRule refusal = null;
            for (ClientRule rule : hits) {
                boolean wantsDeny = rule.action() == ClientRuleAction.DENY && !bypassed;
                if (wantsDeny && refusal == null) {
                    refusal = rule;
                }
                if (dueForReport(owner, rule.key())) {
                    this.reporter.report(player.getUsername(), owner, server, rule, wantsDeny && mayDeny, bypassed);
                }
            }
            if (refusal != null) {
                player.sendMessage(DenyMessages.of(refusal));
                if (mayDeny) {
                    event.setResult(CommandExecuteEvent.CommandResult.denied());
                }
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Command rules could not be checked, allowing the command: {}", e.getMessage());
        }
    }

    /** Removes the commands of hiding rules that cover the player's server from the command tree they are about to receive. */
    public void hide(@NotNull Player player, @NotNull RootCommandNode<?> root) {
        try {
            List<ClientRule> hiding = this.registry.rules().stream()
                    .filter(rule -> rule.kind() == ClientRuleKind.COMMAND && rule.hideCommand()).toList();
            if (hiding.isEmpty() || player.hasPermission(Permissions.CLIENT_RULES_BYPASS)) {
                return;
            }
            String server = serverOf(player);
            List<ClientRule> covering = hiding.stream().filter(rule -> this.engine.applies(rule, server)).toList();
            if (covering.isEmpty()) {
                return;
            }
            for (CommandNode<?> child : List.copyOf(root.getChildren())) {
                Set<String> words = CommandWords.candidates(child.getName());
                if (covering.stream().anyMatch(rule -> words.stream().anyMatch(word -> Globs.matchesAny(rule.patterns(), word)))) {
                    root.removeChildByName(child.getName());
                }
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Command rules could not hide commands, leaving the command list as it is: {}", e.getMessage());
        }
    }

    /** Forgets the dedupe state of a player who left. */
    public void onDisconnect(@NotNull String owner) {
        this.reported.remove(owner);
    }

    private boolean dueForReport(String owner, String ruleKey) {
        Instant now = this.clock.instant();
        Map<String, Instant> byRule = this.reported.computeIfAbsent(owner, key -> new ConcurrentHashMap<>());
        boolean[] due = {false};
        byRule.compute(ruleKey, (key, last) -> {
            if (last == null || !now.isBefore(last.plus(DEDUPE))) {
                due[0] = true;
                return now;
            }
            return last;
        });
        return due[0];
    }

    private static @Nullable String serverOf(Player player) {
        return player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse(null);
    }
}
