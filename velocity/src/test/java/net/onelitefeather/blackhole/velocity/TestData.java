package net.onelitefeather.blackhole.velocity;

import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.velocity.enforcement.PunishmentSyncMessage;
import net.onelitefeather.blackhole.velocity.section.SectionInfo;
import net.onelitefeather.blackhole.velocity.section.SectionRegistry;

import java.util.List;
import java.util.Optional;

/**
 * Small builders shared by the tests; every call creates fresh objects, nothing is shared.
 */
public final class TestData {

    public static final String OWNER = "ab".repeat(64);

    public static final SectionInfo CHAT = new SectionInfo("chat", "Chat", EnforcementKind.MUTE, List.of());
    public static final SectionInfo NETWORK = new SectionInfo("network", "Network", EnforcementKind.BAN, List.of());
    public static final SectionInfo BEDWARS = new SectionInfo("bedwars", "Bedwars", EnforcementKind.SERVER, List.of("bedwars-*"));

    private TestData() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /** A registry that serves exactly these sections. */
    public static SectionRegistry registry(SectionInfo... sections) {
        SectionRegistry registry = new SectionRegistry(() -> List.of(sections));
        registry.reload();
        return registry;
    }

    /** The standard three sections: chat (MUTE), network (BAN), bedwars (SERVER, bedwars-*). */
    public static SectionRegistry standardRegistry() {
        return registry(CHAT, NETWORK, BEDWARS);
    }

    /** An active punishment message of the owner. */
    public static PunishmentSyncMessage active(String section, EnforcementKind kind, String id, Long expiresAt) {
        return new PunishmentSyncMessage(OWNER, section, kind.name(), PunishmentSyncMessage.STATE_SET, id, null, "reason of " + id, expiresAt, false);
    }

    /** A cleared message for the punishment. */
    public static PunishmentSyncMessage cleared(String section, EnforcementKind kind, String id) {
        return new PunishmentSyncMessage(OWNER, section, kind.name(), PunishmentSyncMessage.STATE_CLEARED, id, null, null, null, false);
    }
}
