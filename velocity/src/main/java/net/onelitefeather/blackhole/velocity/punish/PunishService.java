package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.client.model.PunishEntryDTO;
import net.onelitefeather.blackhole.client.model.PunishTemplateResponse;
import net.onelitefeather.blackhole.client.model.PunishmentApplyResponse;
import net.onelitefeather.blackhole.client.model.PunishmentRevokeResponse;
import net.onelitefeather.blackhole.velocity.section.SectionInfo;
import net.onelitefeather.blackhole.velocity.section.SectionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The decisions behind the staff commands, independent of the proxy: which templates a command may
 * use, how an apply or revoke ends. The commands translate the outcomes into messages.
 */
public final class PunishService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PunishService.class);

    /** The kinds of sections {@code /ban} may punish in. */
    public static final Set<EnforcementKind> BAN_KINDS = Set.of(EnforcementKind.BAN, EnforcementKind.SERVER);
    /** The kinds of sections {@code /mute} may punish in. */
    public static final Set<EnforcementKind> MUTE_KINDS = Set.of(EnforcementKind.MUTE);
    /** The kinds of sections {@code /punish} may punish in. */
    public static final Set<EnforcementKind> ANY_KINDS = Set.of(EnforcementKind.values());

    /** How an apply ended. */
    public sealed interface ApplyOutcome {
        /** The template was applied. */
        record Applied(PunishmentApplyResponse response, SectionInfo section) implements ApplyOutcome {
        }

        /** No template has this key. */
        record TemplateNotFound(String key) implements ApplyOutcome {
        }

        /** The template exists but its section is not enforced in a way this command handles. */
        record WrongSection(String key, EnforcementKind kind) implements ApplyOutcome {
        }

        /** The backend refused or could not be reached; {@code status} is the HTTP status or 0. */
        record Failed(int status) implements ApplyOutcome {
        }
    }

    /** How a revoke ended. */
    public sealed interface RevokeOutcome {
        /** The punishment was revoked. */
        record Revoked(PunishmentRevokeResponse response) implements RevokeOutcome {
        }

        /** The section does not exist. */
        record UnknownSection(String section) implements RevokeOutcome {
        }

        /** The section exists but its enforcement is not handled by this command. */
        record WrongSection(String section, EnforcementKind kind) implements RevokeOutcome {
        }

        /** The player has no active punishment in the section. */
        record NothingActive(String section) implements RevokeOutcome {
        }

        /** The punishment is permanent and the sender may not lift those. */
        record PermanentNotAllowed(String section) implements RevokeOutcome {
        }

        /** The backend refused or could not be reached. */
        record Failed(int status) implements RevokeOutcome {
        }
    }

    private final BackendGateway backend;
    private final SectionRegistry sections;

    public PunishService(BackendGateway backend, SectionRegistry sections) {
        this.backend = backend;
        this.sections = sections;
    }

    /**
     * Applies the template with exactly this key, if its section is enforced in one of the allowed
     * ways. Never throws for an expected failure.
     */
    public ApplyOutcome apply(String owner, String templateKey, UUID source, Set<EnforcementKind> allowed) {
        List<PunishTemplateResponse> templates;
        try {
            templates = this.backend.templates();
        } catch (ApiException e) {
            LOGGER.error("Could not load the templates: {}", e.getMessage());
            return new ApplyOutcome.Failed(e.getCode());
        }
        PunishTemplateResponse template = templates.stream().filter(candidate -> templateKey.equals(candidate.getKey())).findFirst().orElse(null);
        if (template == null) {
            return new ApplyOutcome.TemplateNotFound(templateKey);
        }
        SectionInfo section = this.sections.find(template.getSection()).orElse(null);
        if (section == null || !allowed.contains(section.enforcement())) {
            return new ApplyOutcome.WrongSection(templateKey, section == null ? null : section.enforcement());
        }
        try {
            return new ApplyOutcome.Applied(this.backend.apply(owner, templateKey, source), section);
        } catch (ApiException e) {
            LOGGER.error("Applying template {} to {} failed with status {}: {}", templateKey, owner, e.getCode(), e.getMessage());
            return new ApplyOutcome.Failed(e.getCode());
        }
    }

    /**
     * Revokes the owner's active punishment in {@code sectionKey}.
     *
     * @param mayPardonPermanent whether the sender may lift a permanent punishment
     * @param allowed            the enforcement kinds the command handles
     */
    public RevokeOutcome revoke(String owner, String sectionKey, UUID source, boolean mayPardonPermanent, Set<EnforcementKind> allowed) {
        SectionInfo section = this.sections.find(sectionKey).orElse(null);
        if (section == null) {
            return new RevokeOutcome.UnknownSection(sectionKey);
        }
        if (!allowed.contains(section.enforcement())) {
            return new RevokeOutcome.WrongSection(sectionKey, section.enforcement());
        }
        Optional<PunishEntryDTO> active;
        try {
            active = this.backend.profile(owner).map(profile -> profile.getActive() == null ? null : profile.getActive().get(sectionKey));
        } catch (ApiException e) {
            LOGGER.error("Could not load the profile of {}: {}", owner, e.getMessage());
            return new RevokeOutcome.Failed(e.getCode());
        }
        if (active.isEmpty()) {
            return new RevokeOutcome.NothingActive(sectionKey);
        }
        if (Boolean.TRUE.equals(active.get().getPermanent()) && !mayPardonPermanent) {
            return new RevokeOutcome.PermanentNotAllowed(sectionKey);
        }
        try {
            return new RevokeOutcome.Revoked(this.backend.revoke(owner, sectionKey, source));
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                return new RevokeOutcome.NothingActive(sectionKey);
            }
            LOGGER.error("Revoking {} for {} failed with status {}: {}", sectionKey, owner, e.getCode(), e.getMessage());
            return new RevokeOutcome.Failed(e.getCode());
        }
    }

    /**
     * Tab completion: the keys of templates whose section is enforced in an allowed way and which
     * contain the typed text, ignoring case. Empty when the templates cannot be loaded.
     */
    public List<String> suggest(String typed, Set<EnforcementKind> allowed) {
        String needle = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        try {
            return this.backend.templates().stream()
                    .filter(template -> this.sections.find(template.getSection()).map(SectionInfo::enforcement).filter(allowed::contains).isPresent())
                    .map(PunishTemplateResponse::getKey)
                    .filter(key -> key != null && key.toLowerCase(Locale.ROOT).contains(needle))
                    .sorted()
                    .toList();
        } catch (ApiException e) {
            LOGGER.debug("Template suggestions unavailable: {}", e.getMessage());
            return List.of();
        }
    }
}
