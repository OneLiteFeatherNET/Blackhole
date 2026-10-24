package net.onelitefeather.blackhole.backend.clientrule;

import io.micronaut.core.annotation.Nullable;
import net.onelitefeather.blackhole.backend.section.ServerTargetKind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The editable fields of a rule as one value, so validation and persistence do not depend on which
 * request variant (create or update) carried them. The constructor normalises: patterns are lower
 * case and trimmed, missing lists are empty, the scope defaults to {@link ClientRuleScope#GLOBAL},
 * the action to {@link ClientRuleAction#NOTIFY}, and the spoofable acknowledgement is kept only where
 * it applies (a change of kind or action therefore never inherits it).
 */
public record ClientRuleSpec(
        String key,
        String displayName,
        ClientRuleKind kind,
        boolean enabled,
        List<String> patterns,
        @Nullable Integer protocolMin,
        @Nullable Integer protocolMax,
        boolean negate,
        ClientRuleScope scope,
        List<String> sectionKeys,
        ServerTargetKind targetKind,
        List<String> servers,
        ClientRuleAction action,
        @Nullable String denyMessage,
        @Nullable String templateKey,
        boolean acknowledgedSpoofable,
        boolean hideCommand
) {

    public ClientRuleSpec {
        patterns = patterns == null ? List.of() : patterns.stream().map(pattern -> pattern == null ? "" : pattern.strip().toLowerCase(Locale.ROOT)).toList();
        sectionKeys = sectionKeys == null ? List.of() : List.copyOf(sectionKeys);
        servers = servers == null ? List.of() : List.copyOf(servers);
        scope = scope == null ? ClientRuleScope.GLOBAL : scope;
        targetKind = targetKind == null ? ServerTargetKind.PATTERN : targetKind;
        action = action == null ? ClientRuleAction.NOTIFY : action;
        denyMessage = denyMessage == null || denyMessage.isBlank() ? null : denyMessage.strip();
        templateKey = templateKey == null || templateKey.isBlank() ? null : templateKey.strip();
        acknowledgedSpoofable = acknowledgedSpoofable && kind != null && ClientRuleValidator.requiresAcknowledgement(kind, action);
    }

    /** A builder for a valid-by-default rule of the given kind, mostly for tests. */
    public static Builder builder(String key, String displayName, ClientRuleKind kind) {
        return new Builder(key, displayName, kind);
    }

    /** Mutable assembly of a spec; every setter returns the builder. */
    public static final class Builder {

        private String key;
        private String displayName;
        private final ClientRuleKind kind;
        private boolean enabled = true;
        private final List<String> patterns = new ArrayList<>();
        private Integer protocolMin;
        private Integer protocolMax;
        private boolean negate;
        private ClientRuleScope scope = ClientRuleScope.GLOBAL;
        private final List<String> sectionKeys = new ArrayList<>();
        private ServerTargetKind targetKind = ServerTargetKind.PATTERN;
        private final List<String> servers = new ArrayList<>();
        private ClientRuleAction action = ClientRuleAction.NOTIFY;
        private String denyMessage;
        private String templateKey;
        private boolean acknowledged;
        private boolean hideCommand;

        private Builder(String key, String displayName, ClientRuleKind kind) {
            this.key = key;
            this.displayName = displayName;
            this.kind = kind;
        }

        public Builder key(String key) {
            this.key = key;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder disabled() {
            this.enabled = false;
            return this;
        }

        public Builder patterns(String... patterns) {
            this.patterns.clear();
            this.patterns.addAll(Arrays.asList(patterns));
            return this;
        }

        public Builder range(int min, int max) {
            this.protocolMin = min;
            this.protocolMax = max;
            return this;
        }

        public Builder negate() {
            this.negate = true;
            return this;
        }

        public Builder serversScope() {
            this.scope = ClientRuleScope.SERVERS;
            return this;
        }

        public Builder sections(String... sectionKeys) {
            this.sectionKeys.clear();
            this.sectionKeys.addAll(Arrays.asList(sectionKeys));
            return this;
        }

        public Builder targetKind(ServerTargetKind targetKind) {
            this.targetKind = targetKind;
            return this;
        }

        public Builder servers(String... servers) {
            this.servers.clear();
            this.servers.addAll(Arrays.asList(servers));
            return this;
        }

        public Builder action(ClientRuleAction action) {
            this.action = action;
            return this;
        }

        public Builder message(String denyMessage) {
            this.denyMessage = denyMessage;
            return this;
        }

        public Builder template(String templateKey) {
            this.templateKey = templateKey;
            return this;
        }

        public Builder acknowledged() {
            this.acknowledged = true;
            return this;
        }

        public Builder hideCommand() {
            this.hideCommand = true;
            return this;
        }

        public ClientRuleSpec build() {
            return new ClientRuleSpec(this.key, this.displayName, this.kind, this.enabled, this.patterns, this.protocolMin, this.protocolMax,
                    this.negate, this.scope, this.sectionKeys, this.targetKind, this.servers, this.action, this.denyMessage, this.templateKey,
                    this.acknowledged, this.hideCommand);
        }
    }
}
