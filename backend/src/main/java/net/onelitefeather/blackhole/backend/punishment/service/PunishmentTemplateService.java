package net.onelitefeather.blackhole.backend.punishment.service;

import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.clientrule.ClientRuleUsage;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateCache;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateRepository;
import net.onelitefeather.blackhole.backend.punishment.TemplateSpec;
import net.onelitefeather.blackhole.backend.punishment.TemplateStep;
import net.onelitefeather.blackhole.backend.punishment.TemplateValidator;
import net.onelitefeather.blackhole.backend.punishment.dto.PunishTemplateDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.section.SectionService;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;

/**
 * Owns the template repository on behalf of {@code PunishmentTemplateController}: validated CRUD for
 * temporary, sectioned templates with escalation steps. Expected failures are returned as
 * {@link PunishTemplateDTO.Error}.
 */
@Singleton
public class PunishmentTemplateService {

    private final PunishmentTemplateRepository templateRepository;
    private final PunishmentRepository punishmentRepository;
    private final PunishmentTemplateCache templateCache;
    private final SectionService sectionService;
    private final ClientRuleUsage clientRuleUsage;

    public PunishmentTemplateService(
            PunishmentTemplateRepository templateRepository,
            PunishmentRepository punishmentRepository,
            PunishmentTemplateCache templateCache,
            SectionService sectionService,
            ClientRuleUsage clientRuleUsage
    ) {
        this.templateRepository = templateRepository;
        this.punishmentRepository = punishmentRepository;
        this.templateCache = templateCache;
        this.sectionService = sectionService;
        this.clientRuleUsage = clientRuleUsage;
    }

    /**
     * Creates a template. Its escalation group defaults to its key.
     *
     * @return the stored {@link PunishTemplateDTO.Response}, or an {@link PunishTemplateDTO.Error}:
     * validation (400) for a bad definition or unknown section, conflict (409) for a taken key
     */
    public PunishTemplateDTO create(PunishTemplateDTO.CreateRequest request) {
        TemplateSpec spec = TemplateSpec.of(request);
        PunishTemplateDTO.Error problem = problemWith(spec);
        if (problem != null) {
            return problem;
        }
        if (this.templateRepository.findByTemplateKey(spec.key()).isPresent()) {
            return keyTaken(spec.key());
        }
        PunishmentTemplateEntity saved = this.templateRepository.save(toEntity(null, spec));
        this.templateCache.evict(saved.getIdentifier());
        return saved.toDTO();
    }

    /**
     * Replaces every editable field of an existing template. Punishments applied earlier keep the
     * step, duration and cost they recorded.
     *
     * @return the stored {@link PunishTemplateDTO.Response}, or an {@link PunishTemplateDTO.Error}:
     * not found (404), validation (400), or conflict (409) if the key belongs to another template
     */
    public PunishTemplateDTO update(PunishTemplateDTO.UpdateRequest request) {
        if (this.templateRepository.findById(request.identifier()).isEmpty()) {
            return new PunishTemplateDTO.Error("there is no template with identifier " + request.identifier(), ErrorKind.NOT_FOUND);
        }
        TemplateSpec spec = TemplateSpec.of(request);
        PunishTemplateDTO.Error problem = problemWith(spec);
        if (problem != null) {
            return problem;
        }
        Optional<PunishmentTemplateEntity> sameKey = this.templateRepository.findByTemplateKey(spec.key());
        if (sameKey.isPresent() && !sameKey.get().getIdentifier().equals(request.identifier())) {
            return keyTaken(spec.key());
        }
        PunishmentTemplateEntity current = this.templateRepository.findById(request.identifier()).orElseThrow();
        if (current.getTemplateKey() != null && !current.getTemplateKey().equals(spec.key())
                && this.clientRuleUsage.templateInUse(current.getTemplateKey())) {
            return new PunishTemplateDTO.Error("at least one client rule applies this template under its current key, so the key cannot change", ErrorKind.CONFLICT);
        }
        PunishmentTemplateEntity saved = this.templateRepository.update(toEntity(request.identifier(), spec));
        this.templateCache.evict(request.identifier());
        return saved.toDTO();
    }

    /**
     * Deletes the template with {@code identifier}. Checks for referencing punishments first rather
     * than letting the delete fail at the database level: {@code PunishmentEntity.template} is a
     * live FK with no {@code ON DELETE} cascade/set-null configured.
     *
     * @return the removed {@link PunishTemplateDTO.Response}, or an {@link PunishTemplateDTO.Error}:
     * not found (404), or conflict (409) if a punishment still references the template
     */
    public PunishTemplateDTO remove(UUID identifier) {
        PunishmentTemplateEntity entity = this.templateRepository.findById(identifier).orElse(null);
        if (entity == null) {
            return new PunishTemplateDTO.Error("there is no template with identifier " + identifier, ErrorKind.NOT_FOUND);
        }
        if (this.punishmentRepository.existsByTemplate_Identifier(identifier)) {
            return new PunishTemplateDTO.Error("at least one punishment still references this template", ErrorKind.CONFLICT);
        }
        if (entity.getTemplateKey() != null && this.clientRuleUsage.templateInUse(entity.getTemplateKey())) {
            return new PunishTemplateDTO.Error("at least one client rule still applies this template", ErrorKind.CONFLICT);
        }
        this.templateRepository.delete(entity);
        this.templateCache.evict(identifier);
        return entity.toDTO();
    }

    /**
     * Returns a paginated list of all templates. Also refreshes the full list in the shared cache:
     * proxies read that list for tab completion and call this endpoint only when it is missing, so
     * a listing is what repopulates it. Templates are few, so the extra full read is cheap.
     */
    public Page<PunishTemplateDTO.Response> findAll(Pageable pageable) {
        Page<PunishTemplateDTO.Response> page = this.templateRepository.findAll(pageable).map(PunishmentTemplateEntity::toDTO);
        List<PunishTemplateDTO.Response> all = StreamSupport.stream(this.templateRepository.findAll().spliterator(), false)
                .map(PunishmentTemplateEntity::toDTO)
                .toList();
        this.templateCache.putAll(all);
        return page;
    }

    /**
     * Returns the template with {@code identifier}, if any. Served from the shared cache when
     * present; a found template is cached, a missing one is not.
     */
    public Optional<PunishTemplateDTO.Response> find(UUID identifier) {
        Optional<PunishTemplateDTO.Response> cached = this.templateCache.get(identifier);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<PunishTemplateDTO.Response> template = this.templateRepository.findById(identifier).map(PunishmentTemplateEntity::toDTO);
        template.ifPresent(dto -> this.templateCache.put(identifier, dto));
        return template;
    }

    private PunishTemplateDTO.Error problemWith(TemplateSpec spec) {
        List<String> errors = TemplateValidator.validate(spec);
        if (!errors.isEmpty()) {
            return new PunishTemplateDTO.Error(String.join("; ", errors), ErrorKind.VALIDATION);
        }
        if (this.sectionService.find(spec.section()).isEmpty()) {
            return new PunishTemplateDTO.Error("there is no section with key '" + spec.section() + "'", ErrorKind.VALIDATION);
        }
        return null;
    }

    private static PunishTemplateDTO.Error keyTaken(String key) {
        return new PunishTemplateDTO.Error("a template with key '" + key + "' already exists", ErrorKind.CONFLICT);
    }

    private static PunishmentTemplateEntity toEntity(UUID identifier, TemplateSpec spec) {
        PunishmentTemplateEntity entity = new PunishmentTemplateEntity(
                identifier, spec.key(), spec.reason(), spec.section(), spec.effectiveGroup(),
                spec.escalationWindow() == null || spec.escalationWindow().isBlank() ? null : spec.escalationWindow().trim(),
                spec.steps().stream().map(step -> new TemplateStep(step.kind(), step.duration() == null ? null : step.duration().trim(), step.eloCost())).toList(),
                spec.metaData() == null ? new HashMap<>() : new HashMap<>(spec.metaData())
        );
        entity.setWarnWindow(spec.warnWindow() == null || spec.warnWindow().isBlank() ? null : spec.warnWindow().trim());
        return entity;
    }
}
