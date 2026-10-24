package net.onelitefeather.blackhole.velocity.clientrule;

import net.onelitefeather.blackhole.client.model.ClientRuleAction;
import net.onelitefeather.blackhole.client.model.ClientRuleKind;
import net.onelitefeather.blackhole.client.model.ClientRuleScope;
import net.onelitefeather.blackhole.client.model.ServerTargetKind;

import java.util.List;

/** Builds rules for tests; every call creates a fresh one. */
final class Rules {

    private final String key;
    private final ClientRuleKind kind;
    private List<String> patterns = List.of();
    private Integer min;
    private Integer max;
    private boolean negate;
    private ClientRuleScope scope = ClientRuleScope.GLOBAL;
    private List<String> sections = List.of();
    private ServerTargetKind targetKind = ServerTargetKind.PATTERN;
    private List<String> servers = List.of();
    private ClientRuleAction action = ClientRuleAction.NOTIFY;
    private String message;
    private boolean hide;

    private Rules(String key, ClientRuleKind kind) {
        this.key = key;
        this.kind = kind;
    }

    static Rules rule(String key, ClientRuleKind kind) {
        return new Rules(key, kind);
    }

    Rules patterns(String... patterns) {
        this.patterns = List.of(patterns);
        return this;
    }

    Rules range(int min, int max) {
        this.min = min;
        this.max = max;
        return this;
    }

    Rules negate() {
        this.negate = true;
        return this;
    }

    Rules sections(String... sections) {
        this.scope = ClientRuleScope.SERVERS;
        this.sections = List.of(sections);
        return this;
    }

    Rules servers(ServerTargetKind targetKind, String... servers) {
        this.scope = ClientRuleScope.SERVERS;
        this.targetKind = targetKind;
        this.servers = List.of(servers);
        return this;
    }

    Rules action(ClientRuleAction action) {
        this.action = action;
        return this;
    }

    Rules deny() {
        return action(ClientRuleAction.DENY);
    }

    Rules message(String message) {
        this.message = message;
        return this;
    }

    Rules hide() {
        this.hide = true;
        return this;
    }

    ClientRule build() {
        return new ClientRule(this.key, "Rule " + this.key, this.kind, this.patterns, this.min, this.max, this.negate, this.scope, this.sections,
                this.targetKind, this.servers, this.action, this.message, this.hide);
    }
}
