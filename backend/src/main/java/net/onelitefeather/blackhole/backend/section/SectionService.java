package net.onelitefeather.blackhole.backend.section;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.audit.AuditAction;
import net.onelitefeather.blackhole.backend.audit.AuditTarget;
import net.onelitefeather.blackhole.backend.audit.StaffAuditRecorder;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.section.dto.SectionDTO;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

/**
 * Owns the section registry: validated CRUD, the cached reads every ELO write and punishment apply
 * relies on, and the change announcements that make proxies reload the list. Expected failures are
 * returned as {@link SectionDTO.Error}.
 */
@Singleton
public class SectionService {

    private final SectionRepository repository;
    private final SectionCache cache;
    private final DomainEventPublisher eventPublisher;
    private final SectionChangeNotifier notifier;
    private final StaffAuditRecorder audit;

    public SectionService(
            SectionRepository repository,
            SectionCache cache,
            DomainEventPublisher eventPublisher,
            SectionChangeNotifier notifier,
            StaffAuditRecorder audit
    ) {
        this.audit = audit;
        this.repository = repository;
        this.cache = cache;
        this.eventPublisher = eventPublisher;
        this.notifier = notifier;
    }

    /**
     * Creates a section, enabled.
     *
     * @return the stored {@link SectionDTO.Response}, or an {@link SectionDTO.Error} if the
     * definition is invalid or the key is taken
     */
    public SectionDTO create(SectionDTO.CreateRequest request) {
        SectionSpec spec = request.toSpec();
        List<String> errors = SectionValidator.validate(spec);
        if (!errors.isEmpty()) {
            return new SectionDTO.Error(String.join("; ", errors), ErrorKind.VALIDATION);
        }
        if (this.repository.existsById(spec.key())) {
            return new SectionDTO.Error("a section with key '" + spec.key() + "' already exists", ErrorKind.CONFLICT);
        }
        SectionEntity saved = this.repository.save(new SectionEntity(spec, true));
        return changed(saved, "CREATED");
    }

    /**
     * Replaces every editable field of an existing section, including whether it is enabled.
     *
     * @return the stored {@link SectionDTO.Response}, or an {@link SectionDTO.Error} if the section
     * is unknown or the new definition is invalid
     */
    public SectionDTO update(SectionDTO.UpdateRequest request) {
        SectionSpec spec = request.toSpec();
        SectionEntity entity = this.repository.findById(spec.key()).orElse(null);
        if (entity == null) {
            return notFound(spec.key());
        }
        List<String> errors = SectionValidator.validate(spec);
        if (!errors.isEmpty()) {
            return new SectionDTO.Error(String.join("; ", errors), ErrorKind.VALIDATION);
        }
        entity.apply(spec);
        entity.setEnabled(request.enabled());
        return changed(this.repository.update(entity), "UPDATED");
    }

    /**
     * Disables a section: new template applications and score deltas are rejected, existing
     * active punishments stay enforced.
     */
    public SectionDTO disable(String key) {
        SectionEntity entity = this.repository.findById(key).orElse(null);
        if (entity == null) {
            return notFound(key);
        }
        entity.setEnabled(false);
        return changed(this.repository.update(entity), "DISABLED");
    }

    /** {@link #create(SectionDTO.CreateRequest)} by a verified staff member; a stored section is audited. */
    public SectionDTO create(SectionDTO.CreateRequest request, Actor actor) {
        SectionDTO result = create(request);
        if (result instanceof SectionDTO.Response) {
            this.audit.record(actor.attribution(), actor, AuditAction.SECTION_CREATE, AuditTarget.ofSection(request.key()));
        }
        return result;
    }

    /** {@link #update(SectionDTO.UpdateRequest)} by a verified staff member; a stored section is audited. */
    public SectionDTO update(SectionDTO.UpdateRequest request, Actor actor) {
        SectionDTO result = update(request);
        if (result instanceof SectionDTO.Response) {
            this.audit.record(actor.attribution(), actor, AuditAction.SECTION_UPDATE, AuditTarget.ofSection(request.key()));
        }
        return result;
    }

    /** {@link #disable(String)} by a verified staff member; a disabled section is audited. */
    public SectionDTO disable(String key, Actor actor) {
        SectionDTO result = disable(key);
        if (result instanceof SectionDTO.Response) {
            this.audit.record(actor.attribution(), actor, AuditAction.SECTION_DISABLE, AuditTarget.ofSection(key));
        }
        return result;
    }

    /**
     * @return the section as a {@link SectionDTO.Response}, or an {@link SectionDTO.Error} if unknown
     */
    public SectionDTO get(String key) {
        return find(key).<SectionDTO>map(section -> section).orElseGet(() -> notFound(key));
    }

    /**
     * Looks a section up, served from the shared cache when present; a found section is cached,
     * a missing one is not. Disabled sections are returned too - callers check
     * {@link SectionDTO.Response#enabled()}.
     */
    public Optional<SectionDTO.Response> find(String key) {
        Optional<SectionDTO.Response> cached = this.cache.get(key);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<SectionDTO.Response> section = this.repository.findById(key).map(SectionEntity::toResponse);
        section.ifPresent(this.cache::put);
        return section;
    }

    /**
     * All sections, enabled or not, sorted by key. Also refreshes the full list in the shared
     * cache, which is where proxies read it and call this endpoint only when it is missing.
     */
    public List<SectionDTO.Response> list() {
        Optional<List<SectionDTO.Response>> cached = this.cache.getAll();
        if (cached.isPresent()) {
            return cached.get();
        }
        List<SectionDTO.Response> all = StreamSupport.stream(this.repository.findAll().spliterator(), false)
                .map(SectionEntity::toResponse)
                .sorted(java.util.Comparator.comparing(SectionDTO.Response::key))
                .toList();
        this.cache.putAll(all);
        return all;
    }

    /**
     * @return the enabled sections, sorted by key
     */
    public List<SectionDTO.Response> listEnabled() {
        return list().stream().filter(SectionDTO.Response::enabled).toList();
    }

    private SectionDTO.Response changed(SectionEntity entity, String change) {
        this.cache.evict(entity.getSectionKey());
        this.eventPublisher.publish("section.changed", Map.of("section", entity.getSectionKey(), "change", change));
        this.notifier.changed(entity.getSectionKey());
        return entity.toResponse();
    }

    private static SectionDTO.Error notFound(String key) {
        return new SectionDTO.Error("there is no section with key '" + key + "'", ErrorKind.NOT_FOUND);
    }
}
