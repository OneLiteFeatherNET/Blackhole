package net.onelitefeather.blackhole.backend.controller;

import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.validation.Validated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import net.onelitefeather.blackhole.api.metadata.Metadata;
import net.onelitefeather.blackhole.api.profile.PunishProfile;
import net.onelitefeather.blackhole.api.utils.IdGenerator;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentEntity;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentTemplateEntity;
import net.onelitefeather.blackhole.backend.database.repository.PunishmentProfileRepository;
import net.onelitefeather.blackhole.backend.database.repository.PunishmentRepository;
import net.onelitefeather.blackhole.backend.database.repository.PunishmentTemplateRepository;
import net.onelitefeather.blackhole.backend.dto.PunishEntryDTO;
import net.onelitefeather.blackhole.backend.response.PunishProfileResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
     * Set an active punishment to the database.
     *
     * @param owner the owner of the punishment
     * @param templateId the template id of the punishment
     * @param source the source of the punishment
     * @return the added punishment
     */
    @Validated
    @Post("/active/{owner}/{templateId}/{source}")
    public HttpResponse<PunishProfileResponse> add(@Valid @Pattern(regexp = "^[a-fA-F0-9]{128}$", message = "Owner must be a sha-512 hash") String owner, UUID templateId, UUID source) {
        PunishmentProfileEntity profile = this.profileRepository.findById(owner).orElse(null);

        if (profile == null) {
            return HttpResponse.notFound();
        }

        PunishmentTemplateEntity template = this.templateRepository.findById(templateId).orElse(null);

        if (template == null) {
            return HttpResponse.notFound();
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(Metadata.META_DATA_KEY_CREATION_DATE, System.currentTimeMillis());
        metadata.put(Metadata.META_DATA_KEY_UPDATE_DATE, System.currentTimeMillis());

        PunishmentEntity punishDBEntity = new PunishmentEntity(IdGenerator.generateId(), source, template, metadata);
        PunishmentEntity savedEntity = this.punishmentRepository.save(punishDBEntity);

        if (profile.getActiveBan() != null) {
            profile.getHistory().add(profile.getActiveBan());
            profile.setActiveBan(null);
        }


        if (profile.getActiveChatBan() != null) {
            profile.getHistory().add(profile.getActiveChatBan());
            profile.setActiveChatBan(null);
        }


        switch (template.getType()) {
            case CHAT -> profile.setActiveChatBan(savedEntity);
            case SERVER, NETWORK -> profile.setActiveBan(savedEntity);
        }

        this.profileRepository.update(profile);
        return HttpResponse.ok(profile.toDTO());
    }

    /**
     * Get all punishments from the database.
     *
     * @return a list with all punishments
     */
    @Get(value = "/all")
    public HttpResponse<Page<PunishEntryDTO>> getAll(Pageable pageable) {
        Page<PunishmentEntity> entries = this.punishmentRepository.findAll(pageable);
        return HttpResponse.ok(entries.map(PunishmentEntity::toDTO));
    }
}
