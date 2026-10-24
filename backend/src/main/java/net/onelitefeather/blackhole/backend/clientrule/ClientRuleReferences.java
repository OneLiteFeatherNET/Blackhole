package net.onelitefeather.blackhole.backend.clientrule;

/** What a rule may point at, so the service can check it without knowing where it lives. */
public interface ClientRuleReferences {

    /** Whether a section with this key exists and enforces on servers. */
    boolean serverSectionExists(String sectionKey);

    /** Whether a punishment template with this key exists. */
    boolean templateExists(String templateKey);
}
