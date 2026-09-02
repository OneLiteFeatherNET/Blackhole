package net.onelitefeather.blackhole.backend.punishment.service;

import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateRepository;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishTemplateDTO;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishTemplateRequestDTO;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import jakarta.inject.Singleton;

import java.util.Optional;
import java.util.UUID;

/**
 * Owns {@link PunishmentTemplateRepository} on behalf of {@code PunishmentTemplateController},
 * which previously called the repository directly from all 5 endpoints. This replicates the
 * exact same validation/response semantics the controller used to implement inline - a pure
 * layering fix, not a contract change (see {@code micronaut-service-layer}/{@code
 * micronaut-controller-layer}).
 */
@Singleton
public class PunishmentTemplateService {

    private final PunishmentTemplateRepository templateRepository;
    private final PunishmentRepository punishmentRepository;

    public PunishmentTemplateService(PunishmentTemplateRepository templateRepository, PunishmentRepository punishmentRepository) {
        this.templateRepository = templateRepository;
        this.punishmentRepository = punishmentRepository;
    }

    /**
     * Creates a new template. {@code identifier} must be {@code null} for creation - see
     * {@link CreateResult.IdentifierNotAllowed}.
     */
    public CreateResult create(PunishTemplateRequestDTO template) {
        if (template.identifier() != null) {
            return new CreateResult.IdentifierNotAllowed();
        }
        PunishmentTemplateEntity saved = this.templateRepository.save(PunishmentTemplateEntity.toEntity(template));
        return new CreateResult.Created(saved.toDTO());
    }

    /**
     * Updates an existing template. {@code identifier} is required and must reference an
     * existing template - see {@link UpdateResult.MissingIdentifier} / {@link UpdateResult.NotFound}.
     */
    public UpdateResult update(PunishTemplateRequestDTO template) {
        if (template.identifier() == null) {
            return new UpdateResult.MissingIdentifier();
        }
        if (this.templateRepository.findById(template.identifier()).isEmpty()) {
            return new UpdateResult.NotFound();
        }
        PunishmentTemplateEntity saved = this.templateRepository.update(PunishmentTemplateEntity.toEntity(template));
        return new UpdateResult.Updated(saved.toDTO());
    }

    /**
     * Deletes the template with {@code identifier} - see {@link RemoveResult.NotFound} /
     * {@link RemoveResult.InUse}. Checks for referencing punishments first rather than letting
     * the delete fail at the database level: {@code PunishmentEntity.template} is a live FK with
     * no {@code ON DELETE} cascade/set-null configured, so an unchecked delete previously threw
     * an unhandled foreign-key-constraint exception once any punishment had been created from the
     * template - contradicting spec US5 Acceptance Scenario 3 and FR-010's "let network operators
     * ... remove" a template (specs/002-punishment-core/tasks.md T029).
     */
    public RemoveResult remove(UUID identifier) {
        PunishmentTemplateEntity entity = this.templateRepository.findById(identifier).orElse(null);
        if (entity == null) {
            return new RemoveResult.NotFound();
        }
        if (this.punishmentRepository.existsByTemplate_Identifier(identifier)) {
            return new RemoveResult.InUse();
        }
        this.templateRepository.delete(entity);
        return new RemoveResult.Removed(entity.toDTO());
    }

    /**
     * Returns a paginated list of all templates.
     */
    public Page<PunishTemplateDTO> findAll(Pageable pageable) {
        return this.templateRepository.findAll(pageable).map(PunishmentTemplateEntity::toDTO);
    }

    /**
     * Returns the template with {@code identifier}, if any.
     */
    public Optional<PunishTemplateDTO> find(UUID identifier) {
        return this.templateRepository.findById(identifier).map(PunishmentTemplateEntity::toDTO);
    }

    /**
     * Outcome of {@link #create(PunishTemplateRequestDTO)}, mapped 1:1 to an {@code HttpResponse}
     * by the controller instead of the controller re-deriving what happened.
     */
    public sealed interface CreateResult {
        record Created(PunishTemplateDTO template) implements CreateResult {
        }

        record IdentifierNotAllowed() implements CreateResult {
        }
    }

    /**
     * Outcome of {@link #update(PunishTemplateRequestDTO)}, mapped 1:1 to an {@code HttpResponse}
     * by the controller instead of the controller re-deriving what happened.
     */
    public sealed interface UpdateResult {
        record Updated(PunishTemplateDTO template) implements UpdateResult {
        }

        record MissingIdentifier() implements UpdateResult {
        }

        record NotFound() implements UpdateResult {
        }
    }

    /**
     * Outcome of {@link #remove(UUID)}, mapped 1:1 to an {@code HttpResponse} by the controller
     * instead of the controller re-deriving what happened.
     */
    public sealed interface RemoveResult {
        record Removed(PunishTemplateDTO template) implements RemoveResult {
        }

        record NotFound() implements RemoveResult {
        }

        /** At least one punishment still references this template - see {@link #remove(UUID)}. */
        record InUse() implements RemoveResult {
        }
    }
}
