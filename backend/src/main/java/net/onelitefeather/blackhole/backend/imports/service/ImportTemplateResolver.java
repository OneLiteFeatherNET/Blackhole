package net.onelitefeather.blackhole.backend.imports.service;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateRepository;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.section.EnforcementKind;
import net.onelitefeather.blackhole.backend.section.SectionService;
import net.onelitefeather.blackhole.backend.section.dto.SectionDTO;

/**
 * Decides whether a template may carry an imported vanilla ban. Shared by the import and by the
 * re-templating of already imported bans, so both refuse the same templates for the same reasons.
 */
@Singleton
public class ImportTemplateResolver {

    private final PunishmentTemplateRepository templateRepository;
    private final SectionService sectionService;

    public ImportTemplateResolver(PunishmentTemplateRepository templateRepository, SectionService sectionService) {
        this.templateRepository = templateRepository;
        this.sectionService = sectionService;
    }

    /** The outcome of {@link #resolve}. */
    public sealed interface Outcome permits Resolved, Refused {
    }

    /** A template that may be used, with the section it punishes in. */
    public record Resolved(PunishmentTemplateEntity template, SectionDTO.Response section) implements Outcome {
    }

    /** A template that may not be used, and why. */
    public record Refused(ErrorKind kind, String message) implements Outcome {
    }

    /**
     * @return the template with its section, or the reason it is refused: unknown template or section (404),
     * a {@code MUTE} section (400, vanilla bans are bans) or a disabled section (409)
     */
    public Outcome resolve(String key) {
        PunishmentTemplateEntity template = this.templateRepository.findByTemplateKey(key).orElse(null);
        if (template == null) {
            return new Refused(ErrorKind.NOT_FOUND, "there is no punishment template with the key '" + key + "'");
        }
        SectionDTO.Response section = this.sectionService.find(template.getSectionKey()).orElse(null);
        if (section == null) {
            return new Refused(ErrorKind.NOT_FOUND, "the section '" + template.getSectionKey() + "' of template '" + key + "' does not exist");
        }
        if (section.enforcement() == EnforcementKind.MUTE) {
            return new Refused(ErrorKind.VALIDATION, "template '" + key + "' punishes in the mute section '" + section.key()
                    + "'; vanilla bans are bans and cannot be linked to a mute template");
        }
        if (!section.enabled()) {
            return new Refused(ErrorKind.CONFLICT, "section '" + section.key() + "' of template '" + key + "' is disabled");
        }
        return new Resolved(template, section);
    }
}
