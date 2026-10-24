package net.onelitefeather.blackhole.velocity.enforcement;

import net.onelitefeather.blackhole.client.model.PunishEntryDTO;
import net.onelitefeather.blackhole.client.model.PunishProfileDTO;
import net.onelitefeather.blackhole.velocity.section.SectionInfo;
import net.onelitefeather.blackhole.velocity.section.SectionRegistry;
import net.onelitefeather.phoca.metadata.Expirable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns the backend's profile and a freshly applied punishment into the same
 * {@link PunishmentSyncMessage} shape the event bus carries, so the
 * enforcement code has one representation to work with.
 */
public final class ProfileAdapter {

    private static final String REASON_KEY = "reason";
    private static final String AUTOMATIC_KEY = "automatic";

    private ProfileAdapter() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * @return one SET message per active, not lifted punishment of the profile
     */
    public static List<PunishmentSyncMessage> activeMessages(String owner, PunishProfileDTO profile, SectionRegistry sections) {
        List<PunishmentSyncMessage> messages = new ArrayList<>();
        Map<String, PunishEntryDTO> active = profile.getActive();
        if (active == null) {
            return messages;
        }
        active.forEach((section, entry) -> {
            if (!Boolean.TRUE.equals(entry.getLifted())) {
                messages.add(toMessage(owner, section, entry, sections));
            }
        });
        return messages;
    }

    /**
     * @param section the section key the punishment is active in
     */
    public static PunishmentSyncMessage toMessage(String owner, String section, PunishEntryDTO entry, SectionRegistry sections) {
        Map<String, Object> metaData = entry.getMetaData();
        String reason = entry.getTemplate() != null ? entry.getTemplate().getReason() : null;
        if (reason == null && metaData != null && metaData.get(REASON_KEY) instanceof String fromMeta) {
            reason = fromMeta;
        }
        Long expiresAt = null;
        if (!Boolean.TRUE.equals(entry.getPermanent()) && metaData != null
                && metaData.get(Expirable.META_DATA_KEY_EXPIRATION_DATE) instanceof Number number) {
            expiresAt = number.longValue();
        }
        return new PunishmentSyncMessage(
                owner, section,
                sections.find(section).map(SectionInfo::enforcement).map(Enum::name).orElse(null),
                PunishmentSyncMessage.STATE_SET,
                entry.getIdentifier(),
                entry.getTemplate() == null || entry.getTemplate().getIdentifier() == null ? null : entry.getTemplate().getIdentifier().toString(),
                reason,
                expiresAt,
                metaData != null && Boolean.TRUE.equals(metaData.get(AUTOMATIC_KEY))
        );
    }
}
