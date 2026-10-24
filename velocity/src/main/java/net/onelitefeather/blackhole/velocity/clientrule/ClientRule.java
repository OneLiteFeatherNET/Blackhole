package net.onelitefeather.blackhole.velocity.clientrule;

import net.onelitefeather.blackhole.client.model.ClientRuleAction;
import net.onelitefeather.blackhole.client.model.ClientRuleKind;
import net.onelitefeather.blackhole.client.model.ClientRuleResponse;
import net.onelitefeather.blackhole.client.model.ClientRuleScope;
import net.onelitefeather.blackhole.client.model.ServerTargetKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * An enabled client rule as the proxy evaluates it. Patterns are lower-cased globs; nothing here is
 * a regular expression.
 *
 * @param scope       {@code GLOBAL}, or {@code SERVERS} limited by {@code sectionKeys} and/or {@code servers}
 * @param targetKind  how {@code servers} is read, like on a section
 * @param denyMessage the English text a refused player sees, null for the default of the kind
 */
public record ClientRule(
        @NotNull String key,
        @NotNull String displayName,
        @NotNull ClientRuleKind kind,
        @NotNull List<String> patterns,
        @Nullable Integer protocolMin,
        @Nullable Integer protocolMax,
        boolean negate,
        @NotNull ClientRuleScope scope,
        @NotNull List<String> sectionKeys,
        @NotNull ServerTargetKind targetKind,
        @NotNull List<String> servers,
        @NotNull ClientRuleAction action,
        @Nullable String denyMessage,
        boolean hideCommand
) {

    public ClientRule {
        patterns = patterns == null ? List.of() : List.copyOf(patterns);
        sectionKeys = sectionKeys == null ? List.of() : List.copyOf(sectionKeys);
        servers = servers == null ? List.of() : List.copyOf(servers);
        scope = scope == null ? ClientRuleScope.GLOBAL : scope;
        targetKind = targetKind == null ? ServerTargetKind.PATTERN : targetKind;
        action = action == null ? ClientRuleAction.NOTIFY : action;
    }

    /** @return the rule of an API response */
    public static @NotNull ClientRule of(@NotNull ClientRuleResponse response) {
        return new ClientRule(response.getKey(), response.getDisplayName(), response.getKind(), response.getPatterns(),
                response.getProtocolMin(), response.getProtocolMax(), Boolean.TRUE.equals(response.getNegate()), response.getScope(),
                response.getSectionKeys(), response.getTargetKind(), response.getServers(), response.getAction(),
                response.getDenyMessage(), Boolean.TRUE.equals(response.getHideCommand()));
    }
}
