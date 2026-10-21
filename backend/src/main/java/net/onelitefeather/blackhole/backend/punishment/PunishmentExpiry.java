package net.onelitefeather.blackhole.backend.punishment;

import net.onelitefeather.phoca.metadata.Expirable;

import java.util.Map;

/**
 * Shared logic for checking whether an applied punishment has expired. Permanent punishments
 * never carry an expiration date in their metadata, so absence of the key means "not expired".
 */
public final class PunishmentExpiry {

    private PunishmentExpiry() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * Checks whether a punishment has expired.
     *
     * @param punishment the punishment to check
     * @return {@code true} if the punishment carries an expiration date in the past
     */
    public static boolean isExpired(PunishmentEntity punishment) {
        return isExpired(punishment.getMetaData());
    }

    /**
     * Same check as {@link #isExpired(PunishmentEntity)}, for a punishment's metadata alone - used
     * on cached profile DTOs, which carry the metadata but not the entity.
     *
     * @param metaData the punishment's metadata
     * @return {@code true} if the metadata carries an expiration date in the past
     */
    public static boolean isExpired(Map<String, Object> metaData) {
        Object expirationDate = metaData.get(Expirable.META_DATA_KEY_EXPIRATION_DATE);
        return expirationDate instanceof Long millis && millis < System.currentTimeMillis();
    }
}
