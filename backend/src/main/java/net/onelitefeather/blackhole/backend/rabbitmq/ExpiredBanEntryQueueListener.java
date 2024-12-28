package net.onelitefeather.blackhole.backend.rabbitmq;

import io.micronaut.rabbitmq.annotation.Queue;
import io.micronaut.rabbitmq.annotation.RabbitListener;
import jakarta.validation.constraints.NotNull;
import net.onelitefeather.blackhole.api.punish.PunishType;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentEntity;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.database.repository.PunishmentProfileRepository;
import net.onelitefeather.blackhole.backend.database.repository.PunishmentRepository;
import net.onelitefeather.blackhole.backend.database.repository.PunishmentTemplateRepository;
import net.onelitefeather.blackhole.backend.rabbitmq.model.PunishEntryMsg;

import java.util.Objects;
import java.util.Optional;

@RabbitListener
public class ExpiredBanEntryQueueListener {

    private final PunishmentRepository punishmentRepository;
    private final PunishmentProfileRepository profileRepository;

    /**
     * Create a new PunishmentEntityController with the given values.
     *
     * @param punishmentRepository the repository to save the punishments
     * @param profileRepository    the repository to save the profiles
     */
    public ExpiredBanEntryQueueListener(
            PunishmentRepository punishmentRepository,
            PunishmentProfileRepository profileRepository
    ) {
        this.punishmentRepository = punishmentRepository;
        this.profileRepository = profileRepository;
    }

    @Queue("punish-entry-expired")
    public void receive(@NotNull PunishEntryMsg entryMsg) {
        Optional<PunishmentEntity> punishmentEntity = this.punishmentRepository.findById(entryMsg.punishEntryId());
        if (punishmentEntity.isEmpty()) {
            return;
        }
        Optional<PunishmentProfileEntity> punishmentProfile = this.profileRepository.findById(entryMsg.profileId());
        if (punishmentProfile.isEmpty()) {
            return;
        }
        PunishmentEntity punishment = punishmentEntity.get();
        PunishType type = punishment.getTemplate().getType();
        PunishmentProfileEntity profile = punishmentProfile.get();

        if (!Objects.equals(profile.getActiveChatBan().getIdentifier(), punishment.getIdentifier())) return;

        if (type == PunishType.CHAT) {
            profile.setActiveChatBan(null);
            profile.getHistory().add(punishment);
        } else {
            profile.setActiveBan(null);
            profile.getHistory().add(punishment);
        }
        this.profileRepository.update(profile);
    }
}
