package net.onelitefeather.blackhole.backend.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.*;
import net.onelitefeather.blackhole.api.punish.PunishEntry;
import net.onelitefeather.blackhole.backend.database.models.PunishmentEntity;
import net.onelitefeather.blackhole.backend.database.models.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.database.models.PunishmentTemplateEntity;
import net.onelitefeather.blackhole.backend.database.repository.PunishmentProfileRepository;
import net.onelitefeather.blackhole.backend.database.repository.PunishmentRepository;
import net.onelitefeather.blackhole.backend.database.repository.PunishmentTemplateRepository;

import java.util.List;

@Controller(value = "/punishment")
public class PunishmentEntityController {

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
    public PunishmentEntityController(
            PunishmentRepository punishmentRepository,
            PunishmentProfileRepository profileRepository,
            PunishmentTemplateRepository templateRepository
    ) {
        this.punishmentRepository = punishmentRepository;
        this.profileRepository = profileRepository;
        this.templateRepository = templateRepository;
    }

    /**
     * Add a new punishment to the database.
     *
     * @param owner the owner of the punishment
     * @param entry the punishment to add
     * @return the added punishment
     */
    @Post("/add/{owner}")
    public HttpResponse<PunishEntry> add(@PathVariable String owner, @Body PunishEntry entry) {
        PunishmentProfileEntity profile = this.profileRepository.findById(owner).orElse(null);

        if (profile == null) {
            return HttpResponse.notFound();
        }

        PunishmentTemplateEntity template = this.templateRepository.findById(entry.template().identifier()).orElse(null);

        if (template == null) {
            return HttpResponse.notFound();
        }

        PunishmentEntity punishDBEntity = PunishmentEntity.toEntity(entry);
        punishDBEntity.setTemplate(template);

        switch (entry.type()) {
            case CHAT -> profile.setActiveChatBan(punishDBEntity);
            case SERVER, NETWORK -> profile.setActiveBan(punishDBEntity);
        }

        PunishmentEntity savedEntity = this.punishmentRepository.save(punishDBEntity);

        this.profileRepository.update(profile);
        return HttpResponse.ok(savedEntity.toDTO());
    }

    /**
     * Get all punishments from the database.
     *
     * @return a list with all punishments
     */
    @Get(value = "/getAll")
    public HttpResponse<List<PunishEntry>> getAll() {
        List<PunishmentEntity> entries = this.punishmentRepository.findAll();
        return HttpResponse.ok(entries.stream().map(PunishmentEntity::toDTO).toList());
    }
}
