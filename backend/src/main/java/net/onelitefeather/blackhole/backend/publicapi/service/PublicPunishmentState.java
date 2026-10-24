package net.onelitefeather.blackhole.backend.publicapi.service;

import net.onelitefeather.blackhole.backend.appeal.AppealStatus;
import net.onelitefeather.blackhole.backend.publicapi.PublicAppealReader;
import net.onelitefeather.blackhole.backend.publicapi.dto.PublicPunishmentDTO;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentExpiry;

import java.util.List;

/** Where a punishment stands, as the player is told. */
final class PublicPunishmentState {

    private PublicPunishmentState() {
        throw new UnsupportedOperationException("This class cannot be instantiated");
    }

    /**
     * {@code LIFTED} when a granted appeal lifted it, {@code REVOKED} when staff revoked it (both set the same
     * flag on the punishment, the appeals tell them apart), {@code EXPIRED} past its end, else {@code ACTIVE}.
     */
    static PublicPunishmentDTO.State of(PunishmentEntity punishment, List<PublicAppealReader.Row> appeals, long now) {
        if (punishment.isLifted()) {
            boolean liftedByAppeal = appeals.stream().anyMatch(appeal -> appeal.status() == AppealStatus.GRANTED_FULL_LIFT);
            return liftedByAppeal ? PublicPunishmentDTO.State.LIFTED : PublicPunishmentDTO.State.REVOKED;
        }
        return PunishmentExpiry.isExpired(punishment, now) ? PublicPunishmentDTO.State.EXPIRED : PublicPunishmentDTO.State.ACTIVE;
    }
}
