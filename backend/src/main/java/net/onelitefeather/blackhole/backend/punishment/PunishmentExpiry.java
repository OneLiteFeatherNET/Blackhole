package net.onelitefeather.blackhole.backend.punishment;

import net.onelitefeather.phoca.metadata.Expirable;

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
        Object expirationDate = punishment.getMetaData().get(Expirable.META_DATA_KEY_EXPIRATION_DATE);
        return expirationDate instanceof Long millis && millis < System.currentTimeMillis();
    }
}
