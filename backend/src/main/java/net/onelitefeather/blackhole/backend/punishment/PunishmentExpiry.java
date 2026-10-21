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
     * @param now        the current time in epoch millis
     * @return {@code true} if the punishment carries an expiration date before {@code now}
     */
    public static boolean isExpired(PunishmentEntity punishment, long now) {
        return isExpired(punishment.getMetaData(), now);
    }

    /**
     * Same check as {@link #isExpired(PunishmentEntity, long)}, for a punishment's metadata alone -
     * used on cached profile DTOs, which carry the metadata but not the entity.
     *
     * @param metaData the punishment's metadata
     * @param now      the current time in epoch millis
     * @return {@code true} if the metadata carries an expiration date before {@code now}
     */
    public static boolean isExpired(Map<String, Object> metaData, long now) {
        Object expirationDate = metaData.get(Expirable.META_DATA_KEY_EXPIRATION_DATE);
        return expirationDate instanceof Number millis && millis.longValue() < now;
    }
}
