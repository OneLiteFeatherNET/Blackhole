package net.onelitefeather.blackhole.backend.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.*;
import net.onelitefeather.blackhole.api.template.PunishTemplate;
import net.onelitefeather.blackhole.backend.database.models.PunishmentTemplateEntity;
import net.onelitefeather.blackhole.backend.database.repository.PunishmentTemplateRepository;

import java.util.UUID;
import java.util.List;

/**
 * A handler for punishment templates.
 *
 * @author theEvilReaper
 * @version 1.0.0
 * @see PunishTemplate
 * @since 1.0.0
 */
@Controller(value = "/template")
public class PunishmentTemplateHandler {

    private final PunishmentTemplateRepository templateRepository;

    /**
     * Create a new PunishmentTemplateHandler
     *
     * @param templateRepository the repository to use
     */
    public PunishmentTemplateHandler(PunishmentTemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    /**
     * Add a template to the database.
     *
     * @param template the template to add
     * @return the added template
     */
    @Post()
    public HttpResponse<PunishTemplate> addTemplate(@Body PunishTemplate template) {
        PunishmentTemplateEntity dbEntity = PunishmentTemplateEntity.toEntity(template);
        PunishmentTemplateEntity savedEntity = this.templateRepository.save(dbEntity);
        return HttpResponse.ok(savedEntity.toDTO());
    }

    /**
     * Update a template in the database.
     *
     * @param template the template to update
     * @return the updated template
     */
    @Post(value = "/update")
    public HttpResponse<PunishTemplate> updateTemplate(@Body PunishTemplate template) {
        PunishmentTemplateEntity dbEntity = PunishmentTemplateEntity.toEntity(template);

        if (!this.templateRepository.existsById(dbEntity.getIdentifier())) {
            return HttpResponse.notFound();
        }

        PunishmentTemplateEntity savedEntity = this.templateRepository.update(dbEntity);
        return HttpResponse.ok(savedEntity.toDTO());
    }

    /**
     * Remove a template from the database.
     *
     * @param identifier the identifier of the template to remove
     * @return the removed template
     */
    @Delete(value = "/delete/{identifier}")
    public HttpResponse<PunishTemplate> removeTemplate(@PathVariable UUID identifier) {
        PunishmentTemplateEntity entity = this.templateRepository.findById(identifier).orElse(null);

        if (entity == null) {
            return HttpResponse.notFound();
        }

        return HttpResponse.ok(entity.toDTO());
    }

    /**
     * Get all templates from the database.
     *
     * @return a list of all templates
     */
    @Get("/getAll")
    public HttpResponse<List<PunishTemplate>> getAll() {
        List<PunishmentTemplateEntity> entities = this.templateRepository.findAll();
        return HttpResponse.ok(entities.stream().map(PunishmentTemplateEntity::toDTO).toList());
    }
}
