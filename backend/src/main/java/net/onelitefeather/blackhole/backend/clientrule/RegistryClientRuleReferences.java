package net.onelitefeather.blackhole.backend.clientrule;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateRepository;
import net.onelitefeather.blackhole.backend.section.EnforcementKind;
import net.onelitefeather.blackhole.backend.section.SectionService;

/** {@link ClientRuleReferences} backed by the section registry and the template table. */
@Singleton
public class RegistryClientRuleReferences implements ClientRuleReferences {

    private final SectionService sectionService;
    private final PunishmentTemplateRepository templateRepository;

    public RegistryClientRuleReferences(SectionService sectionService, PunishmentTemplateRepository templateRepository) {
        this.sectionService = sectionService;
        this.templateRepository = templateRepository;
    }

    @Override
    public boolean serverSectionExists(String sectionKey) {
        return this.sectionService.find(sectionKey).filter(section -> section.enforcement() == EnforcementKind.SERVER).isPresent();
    }

    @Override
    public boolean templateExists(String templateKey) {
        return this.templateRepository.findByTemplateKey(templateKey).isPresent();
    }
}
