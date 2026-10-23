package net.onelitefeather.blackhole.backend.punishment;

import net.onelitefeather.blackhole.backend.section.dto.SectionDTO;
import net.onelitefeather.phoca.metadata.Expirable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Payloads of the punishment domain events, so every publisher (apply, escalation, import, expiry,
 * revoke, appeal) describes a punishment the same way. The Redis mirror reads these payloads.
 *
 * <p>All values are hashed owner / identifiers only, never raw player UUIDs.</p>
 */
public final class PunishmentEvents {

    /** Shown to the player when nothing more specific exists. */
    public static final String DEFAULT_REASON = "Punished";

    /**
     * Payload of {@code punishment.created} (and {@code punishment.updated}): everything a proxy
     * needs to enforce the punishment.
     *
     * @param section the punishment's section, or {@code null} if it no longer exists
     */
    public static Map<String, Object> created(PunishmentEntity punishment, SectionDTO.Response section) {
        Map<String, Object> payload = base(punishment, section);
        payload.put("permanent", punishment.isPermanent());
        payload.put("reason", reasonOf(punishment));
        if (punishment.getTemplate() != null) {
            payload.put("templateIdentifier", punishment.getTemplate().getIdentifier().toString());
        }
        Object expiresAt = punishment.getMetaData().get(Expirable.META_DATA_KEY_EXPIRATION_DATE);
        if (expiresAt != null) {
            payload.put("expiresAt", expiresAt);
        }
        return payload;
    }

    /**
     * Payload of {@code punishment.expired}.
     */
    public static Map<String, Object> expired(PunishmentEntity punishment, SectionDTO.Response section) {
        return base(punishment, section);
    }

    /**
     * Payload of {@code punishment.revoked}. {@code recordedByOid} is the verified principal that
     * revoked it; {@code verified} says whether there is one, so a consumer never mistakes an
     * unverified attribution for a verified one.
     */
    public static Map<String, Object> revoked(
            PunishmentEntity punishment, SectionDTO.Response section, String revokedBy, UUID recordedByOid
    ) {
        Map<String, Object> payload = base(punishment, section);
        payload.put("revokedBy", revokedBy);
        payload.put("verified", recordedByOid != null);
        if (recordedByOid != null) {
            payload.put("recordedByOid", recordedByOid.toString());
        }
        return payload;
    }

    /**
     * The reason shown to the player: the template's reason, else the reason recorded on the
     * punishment (system punishments), else {@link #DEFAULT_REASON}.
     */
    public static String reasonOf(PunishmentEntity punishment) {
        if (punishment.getTemplate() != null) {
            return punishment.getTemplate().getReason();
        }
        return punishment.getMetaData().get("reason") instanceof String reason ? reason : DEFAULT_REASON;
    }

    private static Map<String, Object> base(PunishmentEntity punishment, SectionDTO.Response section) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("owner", punishment.getOwner());
        payload.put("punishmentIdentifier", punishment.getIdentifier());
        payload.put("section", punishment.getSectionKey());
        if (section != null) {
            payload.put("enforcement", section.enforcement().name());
        }
        return payload;
    }

    private PunishmentEvents() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }
}
