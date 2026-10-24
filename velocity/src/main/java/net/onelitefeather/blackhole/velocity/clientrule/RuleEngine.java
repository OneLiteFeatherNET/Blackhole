package net.onelitefeather.blackhole.velocity.clientrule;

import net.onelitefeather.blackhole.client.model.ClientRuleKind;
import net.onelitefeather.blackhole.client.model.ClientRuleScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Evaluates client rules against the traits of one player. A pure function: no backend round trip, no
 * clock, no proxy, so enforcement keeps working when the backend is down.
 *
 * <p>Rules about values the client announces (brand, mods, channels) are checked against what is known
 * so far; an unknown value never matches, not even a negated rule: an allowlist on the brand is
 * bypassable by suppressing the brand, which is written down, not fixed. A rule limited to servers
 * applies only when the server the player is on or connecting to is known and covered.</p>
 */
public final class RuleEngine {

    /** Whether a scoped rule covers a backend server; the production implementation reads sections and CloudNet. */
    @FunctionalInterface
    public interface Coverage {
        boolean covers(@NotNull ClientRule rule, @NotNull String serverName);
    }

    private final Coverage coverage;

    public RuleEngine(@NotNull Coverage coverage) {
        this.coverage = coverage;
    }

    /**
     * @param traits     what is known about the client
     * @param serverName the backend server the player is on or connecting to, null if none yet
     * @param kinds      the kinds to check; {@link ClientRuleKind#COMMAND} is never checked here
     * @param rules      the enabled rules
     * @return the rules that hit, in the order of {@code rules}
     */
    public @NotNull List<ClientRule> hits(@NotNull ClientTraits traits, @Nullable String serverName, @NotNull Set<ClientRuleKind> kinds,
                                          @NotNull List<ClientRule> rules) {
        List<ClientRule> hits = new ArrayList<>();
        for (ClientRule rule : rules) {
            if (kinds.contains(rule.kind()) && applies(rule, serverName) && matches(rule, traits)) {
                hits.add(rule);
            }
        }
        return hits;
    }

    /**
     * The command rules a command line hits. A rule limited to servers applies to the server the player is on;
     * without a current server only global rules apply. Arguments are never matched.
     *
     * @param words      the candidate words of the line, see {@link CommandWords#candidates}
     * @param serverName the backend server the player is on, null if none
     */
    public @NotNull List<ClientRule> commandHits(@NotNull Set<String> words, @Nullable String serverName, @NotNull List<ClientRule> rules) {
        List<ClientRule> hits = new ArrayList<>();
        for (ClientRule rule : rules) {
            if (rule.kind() == ClientRuleKind.COMMAND && applies(rule, serverName) && words.stream().anyMatch(word -> Globs.matchesAny(rule.patterns(), word))) {
                hits.add(rule);
            }
        }
        return hits;
    }

    /** @return whether the rule's scope includes the server (global rules always do) */
    public boolean applies(@NotNull ClientRule rule, @Nullable String serverName) {
        if (rule.scope() == ClientRuleScope.GLOBAL) {
            return true;
        }
        return serverName != null && this.coverage.covers(rule, serverName);
    }

    /** @return whether the traits match the rule's data, ignoring its scope */
    public boolean matches(@NotNull ClientRule rule, @NotNull ClientTraits traits) {
        return switch (rule.kind()) {
            case BRAND -> traits.brand() != null && (Globs.matchesAny(rule.patterns(), traits.brand()) != rule.negate());
            case MOD -> traits.mods().stream().anyMatch(mod -> matchesMod(rule, mod));
            case CHANNEL -> traits.channels().stream().anyMatch(channel -> Globs.matchesAny(rule.patterns(), channel));
            case PROTOCOL -> traits.protocol() >= 0 && inRange(rule, traits.protocol()) != rule.negate();
            case COMMAND -> false;
        };
    }

    private static boolean matchesMod(ClientRule rule, String mod) {
        int at = mod.indexOf('@');
        String id = at < 0 ? mod : mod.substring(0, at);
        return Globs.matchesAny(rule.patterns(), mod) || Globs.matchesAny(rule.patterns(), id);
    }

    private static boolean inRange(ClientRule rule, int protocol) {
        return rule.protocolMin() != null && rule.protocolMax() != null && protocol >= rule.protocolMin() && protocol <= rule.protocolMax();
    }
}
