package net.onelitefeather.blackhole.backend.imports.service;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.casetimeline.CaseTimeline;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.imports.dto.ImportedPunishmentDTO;
import net.onelitefeather.blackhole.backend.profile.ProfileCache;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileRepository;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEvents;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.Attribution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Links punishments that were imported before imports needed a template to a template afterwards.
 *
 * <p>Only the template changes: no score, ELO event, escalation field, duration or section is touched,
 * and a punishment that already has a template is never overwritten, so running the same request
 * again changes nothing. {@code @Transactional} is unusable in this codebase, so each punishment is
 * its own write; a request that stops half way is simply run again.</p>
 */
@Singleton
public class ImportedPunishmentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImportedPunishmentService.class);

    private final PunishmentRepository punishmentRepository;
    private final PunishmentProfileRepository profileRepository;
    private final ImportTemplateResolver templateResolver;
    private final DomainEventPublisher eventPublisher;
    private final ProfileCache profileCache;
    private final CaseTimeline caseTimeline;
    private final Clock clock;

    public ImportedPunishmentService(
            PunishmentRepository punishmentRepository,
            PunishmentProfileRepository profileRepository,
            ImportTemplateResolver templateResolver,
            DomainEventPublisher eventPublisher,
            ProfileCache profileCache,
            CaseTimeline caseTimeline,
            Clock clock
    ) {
        this.punishmentRepository = punishmentRepository;
        this.profileRepository = profileRepository;
        this.templateResolver = templateResolver;
        this.eventPublisher = eventPublisher;
        this.profileCache = profileCache;
        this.caseTimeline = caseTimeline;
        this.clock = clock;
    }

    /** The imported punishments that still have no template, grouped by original reason, the largest group first. */
    public ImportedPunishmentDTO.Groups groups() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<PunishmentEntity> unlinked = unlinkedImports();
        unlinked.forEach(punishment -> counts.merge(originalReason(punishment), 1, Integer::sum));
        List<ImportedPunishmentDTO.Group> groups = counts.entrySet().stream()
                .map(group -> new ImportedPunishmentDTO.Group(group.getKey(), group.getValue()))
                .sorted(Comparator.comparingInt(ImportedPunishmentDTO.Group::count).reversed()
                        .thenComparing(ImportedPunishmentDTO.Group::reason))
                .toList();
        return new ImportedPunishmentDTO.Groups(unlinked.size(), groups);
    }

    /**
     * Links the selected imported punishments to the template.
     *
     * @return the {@link ImportedPunishmentDTO.RetemplateResponse}, or an {@link ImportedPunishmentDTO.Error} (nothing
     * changed) for an invalid selection, an unknown template or one in a mute or disabled section
     */
    public ImportedPunishmentDTO retemplate(ImportedPunishmentDTO.RetemplateRequest request, Actor actor) {
        boolean hasReason = request.reason() != null && !request.reason().isBlank();
        boolean hasIdentifiers = request.identifiers() != null && !request.identifiers().isEmpty();
        if (hasReason == hasIdentifiers) {
            return error(ErrorKind.VALIDATION, "exactly one of reason and identifiers is required");
        }
        ImportTemplateResolver.Resolved resolved;
        switch (this.templateResolver.resolve(request.templateKey())) {
            case ImportTemplateResolver.Refused refused -> {
                return error(refused.kind(), refused.message());
            }
            case ImportTemplateResolver.Resolved ok -> resolved = ok;
        }

        Selection selected = hasReason ? new Selection(withOriginalReason(request.reason()), 0) : byIdentifiers(request.identifiers());
        Attribution by = actor.attribution();
        int retemplated = 0;
        int unchanged = 0;
        // An identifier that matches nothing is counted like one that is not an imported punishment.
        int skipped = selected.unknown();
        for (PunishmentEntity punishment : selected.found()) {
            if (!isImported(punishment)) {
                skipped++;
            } else if (punishment.getTemplate() != null) {
                if (punishment.getTemplate().getIdentifier().equals(resolved.template().getIdentifier())) {
                    unchanged++;
                } else {
                    skipped++;
                }
            } else if (!resolved.section().key().equals(punishment.getSectionKey())) {
                skipped++;
            } else {
                link(punishment, resolved, by);
                retemplated++;
            }
        }
        return new ImportedPunishmentDTO.RetemplateResponse(request.templateKey(), selected.found().size() + selected.unknown(), retemplated, unchanged, skipped);
    }

    private void link(PunishmentEntity punishment, ImportTemplateResolver.Resolved resolved, Attribution by) {
        punishment.retemplate(resolved.template(), originalReason(punishment), this.clock.millis());
        this.punishmentRepository.update(punishment);
        this.profileCache.evict(punishment.getOwner());
        this.caseTimeline.retemplated(punishment, null, by);
        publishIfActive(punishment, resolved);
    }

    /** An active punishment is announced again so the proxy mirror carries the template's reason; one in history needs no event. */
    private void publishIfActive(PunishmentEntity punishment, ImportTemplateResolver.Resolved resolved) {
        try {
            boolean active = this.profileRepository.findById(punishment.getOwner())
                    .map(profile -> profile.getActive().get(punishment.getSectionKey()))
                    .map(current -> current.getIdentifier().equals(punishment.getIdentifier()))
                    .orElse(false);
            if (active) {
                this.eventPublisher.publish("punishment.updated", PunishmentEvents.created(punishment, resolved.section()));
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Could not announce the re-templated punishment {}; the proxy shows the old reason until it is next synced",
                    punishment.getIdentifier(), e);
        }
    }

    private List<PunishmentEntity> unlinkedImports() {
        return this.punishmentRepository.findBySourceAndTemplateIsNull(VanillaImportService.SYSTEM_IMPORT_SOURCE);
    }

    private List<PunishmentEntity> withOriginalReason(String reason) {
        String wanted = VanillaImportService.normalizedReason(reason);
        return unlinkedImports().stream().filter(punishment -> originalReason(punishment).equals(wanted)).toList();
    }

    /** The punishments a request selected, and how many of its identifiers matched none. */
    private record Selection(List<PunishmentEntity> found, int unknown) {
    }

    private Selection byIdentifiers(List<String> identifiers) {
        List<PunishmentEntity> found = new ArrayList<>();
        Set<String> distinct = new HashSet<>(identifiers);
        distinct.forEach(identifier -> this.punishmentRepository.findById(identifier).ifPresent(found::add));
        return new Selection(found, distinct.size() - found.size());
    }

    private static boolean isImported(PunishmentEntity punishment) {
        return VanillaImportService.SYSTEM_IMPORT_SOURCE.equals(punishment.getSource());
    }

    /** What the vanilla file said: {@code importedReason}, else the {@code reason} older imports stored. */
    static String originalReason(PunishmentEntity punishment) {
        Object imported = punishment.getMetaData().get(VanillaImportService.IMPORTED_REASON);
        Object legacy = punishment.getMetaData().get("reason");
        return VanillaImportService.normalizedReason(imported instanceof String text ? text : legacy instanceof String text ? text : null);
    }

    private static ImportedPunishmentDTO.Error error(ErrorKind kind, String message) {
        return new ImportedPunishmentDTO.Error(message, kind);
    }
}
