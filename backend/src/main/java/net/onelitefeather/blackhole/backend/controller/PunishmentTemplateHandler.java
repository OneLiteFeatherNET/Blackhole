package net.onelitefeather.blackhole.backend.controller;

import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import jakarta.validation.Valid;
import net.onelitefeather.blackhole.api.template.PunishTemplate;
import net.onelitefeather.blackhole.backend.database.entities.PunishmentTemplateEntity;
import net.onelitefeather.blackhole.backend.database.repository.PunishmentTemplateRepository;
import net.onelitefeather.blackhole.backend.dto.PunishTemplateDTO;

import java.util.Optional;
import java.util.UUID;

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
    @Post("/")
    public HttpResponse<PunishTemplateDTO> addTemplate(@Valid @Body PunishTemplateDTO template) {
        if (template.identifier() != null) {
            return HttpResponse.notAllowed();
        }
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
    public HttpResponse<PunishTemplateDTO> updateTemplate(@Valid @Body PunishTemplateDTO template) {
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
    public HttpResponse<PunishTemplateDTO> removeTemplate(@PathVariable UUID identifier) {
        PunishmentTemplateEntity entity = this.templateRepository.findById(identifier).orElse(null);

        if (entity == null) {
            return HttpResponse.notFound();
        }
        this.templateRepository.delete(entity);

        return HttpResponse.ok(entity.toDTO());
    }

    /**
     * Get all templates from the database.
     *
     * @return a list of all templates
     */
    @Get("/all")
    public HttpResponse<Page<PunishTemplateDTO>> getAll(Pageable pageable) {
        Page<PunishmentTemplateEntity> entities = this.templateRepository.findAll(pageable);
        return HttpResponse.ok(entities.map(PunishmentTemplateEntity::toDTO));
    }

    /**
     * Get all templates from the database.
     *
     * @return a list of all templates
     */
    @Get("/{identifier}")
    public HttpResponse<PunishTemplateDTO> get(UUID identifier) {
        Optional<PunishmentTemplateEntity> entity = this.templateRepository.findById(identifier);
        return entity.map(PunishmentTemplateEntity::toDTO)
                .map(HttpResponse::ok)
                .orElseGet(HttpResponse::notFound);
    }
}
