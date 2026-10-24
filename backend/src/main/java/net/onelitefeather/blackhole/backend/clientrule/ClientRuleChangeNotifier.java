package net.onelitefeather.blackhole.backend.clientrule;

/**
 * Tells every proxy that the rules changed, so it reloads them. Best effort: a failure to notify
 * must never fail the write that triggered it (proxies also reload when their copy expires).
 */
public interface ClientRuleChangeNotifier {

    /**
     * @param ruleKey the rule that was created, updated or deleted
     */
    void changed(String ruleKey);
}
