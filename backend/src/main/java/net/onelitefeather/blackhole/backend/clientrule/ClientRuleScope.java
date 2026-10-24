package net.onelitefeather.blackhole.backend.clientrule;

import io.micronaut.serde.annotation.Serdeable;

/** Where a rule applies. */
@Serdeable
public enum ClientRuleScope {

    /** On the whole network. */
    GLOBAL,

    /** Only on the servers its sections and own targets cover. */
    SERVERS
}
