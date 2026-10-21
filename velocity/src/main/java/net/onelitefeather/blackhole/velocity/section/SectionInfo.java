package net.onelitefeather.blackhole.velocity.section;

import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.client.model.SectionResponse;

import java.util.List;

/**
 * What the proxy needs to know about a punishment section: how it is enforced and, for SERVER
 * sections, which backend servers it covers.
 *
 * @param key         the section key, e.g. {@code network}
 * @param displayName the human readable name
 * @param enforcement MUTE (chat), BAN (login) or SERVER (connect to matching servers)
 * @param servers     glob patterns of the covered backend servers; empty unless SERVER
 */
public record SectionInfo(String key, String displayName, EnforcementKind enforcement, List<String> servers) {

    public SectionInfo {
        servers = servers == null ? List.of() : List.copyOf(servers);
    }

    public static SectionInfo of(SectionResponse response) {
        return new SectionInfo(response.getKey(), response.getDisplayName(), response.getEnforcement(), response.getServers());
    }
}
