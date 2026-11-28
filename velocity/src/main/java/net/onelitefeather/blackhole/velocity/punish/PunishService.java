package net.onelitefeather.blackhole.velocity.punish;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.EnforcementKind;
import net.onelitefeather.blackhole.client.model.PunishEntryDTO;
import net.onelitefeather.blackhole.client.model.PunishTemplateResponse;
import net.onelitefeather.blackhole.client.model.PunishmentApplyResponse;
import net.onelitefeather.blackhole.client.model.PunishmentRevokeResponse;
import net.onelitefeather.blackhole.velocity.evidence.ChatEvidence;
import net.onelitefeather.blackhole.velocity.section.SectionInfo;
import net.onelitefeather.blackhole.velocity.section.SectionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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

    /** How many similar template keys an unknown key is answered with. */
    public static final int SIMILAR_LIMIT = 5;

    /** The shortest common prefix that makes two keys look alike. */
    private static final int MIN_SHARED_PREFIX = 3;
    /** The most single-character edits that still make two keys look alike. */
    private static final int MAX_EDIT_DISTANCE = 2;

    /** How an apply ended. */
    public sealed interface ApplyOutcome {
        /** The template was applied. */
        record Applied(PunishmentApplyResponse response, SectionInfo section, ChatEvidence.Result evidence) implements ApplyOutcome {
        }

        /**
         * No template has this key.
         *
         * @param similar up to {@link #SIMILAR_LIMIT} keys of eligible templates that resemble the typed key, best first
         */
        record TemplateNotFound(String key, List<String> similar) implements ApplyOutcome {

            public TemplateNotFound {
                similar = List.copyOf(similar);
            }
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
    private final ChatEvidence chatEvidence;

    public PunishService(BackendGateway backend, SectionRegistry sections, ChatEvidence chatEvidence) {
        this.backend = backend;
        this.sections = sections;
        this.chatEvidence = chatEvidence;
    }

    /**
     * Applies the template with exactly this key, if its section is enforced in one of the allowed
     * ways. If chat capture is on, the target's buffered chat is posted as evidence first and claimed
     * by the punishment; a failing upload never stops the punishment. Never throws for an expected failure.
     *
     * @param playerUuid the player's raw UUID, or null when unknown; see {@link BackendGateway#apply}
     * @param warningOnly whether the step the ladder reaches must be a warning; see {@link BackendGateway#apply}
     */
    public ApplyOutcome apply(String owner, UUID playerUuid, String templateKey, UUID source, Set<EnforcementKind> allowed, boolean warningOnly) {
        List<PunishTemplateResponse> templates;
        try {
            templates = this.backend.templates();
        } catch (ApiException e) {
            LOGGER.error("Could not load the templates: {}", e.getMessage());
            return new ApplyOutcome.Failed(e.getCode());
        }
        PunishTemplateResponse template = templates.stream().filter(candidate -> templateKey.equals(candidate.getKey())).findFirst().orElse(null);
        if (template == null) {
            return new ApplyOutcome.TemplateNotFound(templateKey, closest(templateKey, eligibleKeys(templates, allowed), SIMILAR_LIMIT));
        }
        SectionInfo section = this.sections.find(template.getSection()).orElse(null);
        if (section == null || !allowed.contains(section.enforcement())) {
            return new ApplyOutcome.WrongSection(templateKey, section == null ? null : section.enforcement());
        }
        ChatEvidence.Result evidence = this.chatEvidence.attach(owner, source);
        try {
            return new ApplyOutcome.Applied(this.backend.apply(owner, playerUuid, templateKey, source, evidence.correlationId(), warningOnly), section, evidence);
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
            return eligibleKeys(this.backend.templates(), allowed).stream()
                    .filter(key -> key.toLowerCase(Locale.ROOT).contains(needle))
                    .sorted()
                    .toList();
        } catch (ApiException e) {
            LOGGER.debug("Template suggestions unavailable: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Ranks template keys by how much they look like what was typed, ignoring case: keys containing the
     * text first, then keys sharing a prefix of at least three characters, then keys within two edits.
     * Pure, so it needs no backend.
     *
     * @return at most {@code limit} keys, best first, ties in alphabetical order
     */
    public static List<String> closest(String typed, Collection<String> keys, int limit) {
        String needle = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return List.of();
        }
        return keys.stream()
                .map(key -> new Ranked(key, rank(needle, key.toLowerCase(Locale.ROOT))))
                .filter(ranked -> ranked.rank() >= 0)
                .sorted(Comparator.comparingInt(Ranked::rank).thenComparing(Ranked::key))
                .limit(limit)
                .map(Ranked::key)
                .toList();
    }

    private record Ranked(String key, int rank) {
    }

    /** @return 0 for contains, 1 for a shared prefix, 2 + distance for a near miss, -1 for no resemblance */
    private static int rank(String needle, String key) {
        if (key.contains(needle)) {
            return 0;
        }
        int prefix = 0;
        while (prefix < needle.length() && prefix < key.length() && needle.charAt(prefix) == key.charAt(prefix)) {
            prefix++;
        }
        if (prefix >= MIN_SHARED_PREFIX) {
            return 1;
        }
        int distance = editDistance(needle, key);
        return distance <= MAX_EDIT_DISTANCE ? 2 + distance : -1;
    }

    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int[] current = new int[b.length() + 1];
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            previous = current;
        }
        return previous[b.length()];
    }

    private List<String> eligibleKeys(List<PunishTemplateResponse> templates, Set<EnforcementKind> allowed) {
        return templates.stream()
                .filter(template -> this.sections.find(template.getSection()).map(SectionInfo::enforcement).filter(allowed::contains).isPresent())
                .map(PunishTemplateResponse::getKey)
                .filter(Objects::nonNull)
                .toList();
    }
}
