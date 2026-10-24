package net.onelitefeather.blackhole.backend.clientrule;

import io.micronaut.serde.annotation.Serdeable;

/** What happens when a player hits a rule. */
@Serdeable
public enum ClientRuleAction {

    /** Record the hit and tell the team; nothing changes for the player. The default, and the dry run. */
    NOTIFY,

    /** Additionally refuse the connection or the command. */
    DENY,

    /** Additionally apply the rule's punishment template automatically. */
    TEMPLATE
}
