package net.onelitefeather.blackhole.velocity.clientrule;

import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.velocity.cloudnet.ServerFacts;
import net.onelitefeather.blackhole.velocity.cloudnet.ServerResolver;
import net.onelitefeather.blackhole.velocity.section.SectionInfo;
import net.onelitefeather.blackhole.velocity.section.SectionRegistry;
import net.onelitefeather.blackhole.velocity.section.ServerTargets;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Whether a server-limited rule covers a backend server: exactly when the section targeting would. The
 * rule names SERVER sections by key (read live, so editing a section moves the rule) and/or carries its
 * own targets read like a section's. A section that does not exist or is not a SERVER section covers
 * nothing (fail open), as does a server CloudNet does not know for a CloudNet target.
 */
public final class ClientRuleCoverage implements RuleEngine.Coverage {

    private final SectionRegistry sections;
    private final ServerResolver servers;

    public ClientRuleCoverage(@NotNull SectionRegistry sections, @NotNull ServerResolver servers) {
        this.sections = sections;
        this.servers = servers;
    }

    @Override
    public boolean covers(@NotNull ClientRule rule, @NotNull String serverName) {
        Optional<ServerFacts> facts = this.servers.facts(serverName);
        for (String key : rule.sectionKeys()) {
            if (this.sections.find(key).filter(section -> section.enforcement() == EnforcementKind.SERVER)
                    .filter(section -> ServerTargets.covers(section, serverName, facts)).isPresent()) {
                return true;
            }
        }
        return !rule.servers().isEmpty()
                && ServerTargets.covers(new SectionInfo(rule.key(), rule.displayName(), EnforcementKind.SERVER, rule.servers(), rule.targetKind()),
                serverName, facts);
    }
}
