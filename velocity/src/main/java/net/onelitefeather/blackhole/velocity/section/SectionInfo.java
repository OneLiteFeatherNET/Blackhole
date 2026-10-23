package net.onelitefeather.blackhole.velocity.section;

import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.client.model.SectionResponse;
import net.onelitefeather.blackhole.client.model.ServerTargetKind;

import java.util.List;

/**
 * What the proxy needs to know about a punishment section: how it is enforced and, for SERVER
 * sections, which backend servers it covers.
 *
 * @param key         the section key, e.g. {@code network}
 * @param displayName the human readable name
 * @param enforcement MUTE (chat), BAN (login) or SERVER (connect to matching servers)
 * @param servers     the covered backend servers: glob patterns, or CloudNet names for the CloudNet
 *                    kinds; empty unless SERVER
 * @param targetKind  how {@code servers} is read; {@code PATTERN} if the section list does not say
 */
public record SectionInfo(String key, String displayName, EnforcementKind enforcement, List<String> servers,
                          ServerTargetKind targetKind) {

    public SectionInfo {
        servers = servers == null ? List.of() : List.copyOf(servers);
        targetKind = targetKind == null ? ServerTargetKind.PATTERN : targetKind;
    }

    public SectionInfo(String key, String displayName, EnforcementKind enforcement, List<String> servers) {
        this(key, displayName, enforcement, servers, ServerTargetKind.PATTERN);
    }

    public static SectionInfo of(SectionResponse response) {
        return new SectionInfo(response.getKey(), response.getDisplayName(), response.getEnforcement(),
                response.getServers(), response.getTargetKind());
    }
}
