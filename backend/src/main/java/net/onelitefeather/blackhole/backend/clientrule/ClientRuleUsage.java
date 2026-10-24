package net.onelitefeather.blackhole.backend.clientrule;

/** Asked by the template service before it deletes or renames a template. */
public interface ClientRuleUsage {

    /** Whether any rule applies the template with this key. */
    boolean templateInUse(String templateKey);
}
