package net.onelitefeather.blackhole.backend.publicbans;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateEntity;
import net.onelitefeather.blackhole.backend.section.EnforcementKind;

import java.util.UUID;

/**
 * Writes the public listing columns of a punishment: on apply (the template's public reason is copied, the entry hash
 * and the UTC day are set), on revoke (the revoke instant), and on erasure (the listing and the internal UUID are
 * cleared). Only a template with a non-blank public reason lists anything; kicks and warnings never reach this class.
 */
@Singleton
public class PublicBanListing {

    private final PublicBanHasher hasher;
    private final PunishmentRepository punishments;

    public PublicBanListing(PublicBanHasher hasher, PunishmentRepository punishments) {
        this.hasher = hasher;
        this.punishments = punishments;
    }

    /**
     * Stores the player UUID of the apply request and, when the template has a public reason, lists the punishment.
     *
     * @param punishment the new punishment (a punishment, not a kick or a warning)
     * @param template   the template it came from, or {@code null}
     * @param section    the enforcement kind of its section
     * @param playerUuid the normalised player UUID, or {@code null} when the caller did not know it
     * @param now        the apply instant in epoch millis
     * @param expiresAt  the expiry instant in epoch millis, or {@code null} for a permanent entry
     */
    public void applied(PunishmentEntity punishment, PunishmentTemplateEntity template, EnforcementKind section,
                        UUID playerUuid, long now, Long expiresAt) {
        punishment.setPlayerUuid(playerUuid);
        String reason = template == null ? null : template.getPublicReason();
        if (reason == null || reason.isBlank() || section == null) {
            return;
        }
        punishment.publish(reason, kindOf(section), this.hasher.entryHash(punishment.getIdentifier()),
                PublicBanDay.of(now), expiresAt);
    }

    /** The public kind of a section: a ban is a network ban on the list. */
    static String kindOf(EnforcementKind section) {
        return switch (section) {
            case BAN -> "NETWORK_BAN";
            case MUTE -> "MUTE";
            case SERVER -> "SERVER";
        };
    }

    /** Records the revoke instant of a listed entry; an unlisted punishment is left alone. */
    public void revoked(PunishmentEntity punishment, long now) {
        punishment.markPublicRevoked(now);
    }

    /** Erasure of an owner: every punishment of that owner loses its listing and its internal UUID. */
    public void erase(String owner) {
        this.punishments.findByOwner(owner).forEach(punishment -> {
            punishment.clearPublicListing();
            this.punishments.update(punishment);
        });
    }
}
