package net.onelitefeather.blackhole.backend.rabbitmq;

import io.micronaut.rabbitmq.annotation.Queue;
import io.micronaut.rabbitmq.annotation.RabbitListener;
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
    private final PunishmentTemplateRepository templateRepository;

    /**
     * Create a new PunishmentEntityController with the given values.
     *
     * @param punishmentRepository the repository to save the punishments
     * @param profileRepository    the repository to save the profiles
     * @param templateRepository   the repository to save the templates
     */
    public ExpiredBanEntryQueueListener(
            PunishmentRepository punishmentRepository,
            PunishmentProfileRepository profileRepository,
            PunishmentTemplateRepository templateRepository
    ) {
        this.punishmentRepository = punishmentRepository;
        this.profileRepository = profileRepository;
        this.templateRepository = templateRepository;
    }

    @Queue("punish-entry-expired")
    public void receive(PunishEntryMsg entryMsg) {
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
        switch (type) {
            case CHAT -> {
                if (!Objects.equals(profile.getActiveChatBan().getIdentifier(), punishment.getIdentifier())) {
                    return;
                }
                profile.setActiveChatBan(null);
                profile.getHistory().add(punishment);
            }
            case NETWORK, SERVER -> {
                if (!Objects.equals(profile.getActiveBan().getIdentifier(), punishment.getIdentifier())) {
                    return;
                }
                profile.setActiveBan(null);
                profile.getHistory().add(punishment);
            }
        }
        this.profileRepository.update(profile);
    }

}
