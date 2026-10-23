package net.onelitefeather.blackhole.backend.imports.service;

import net.onelitefeather.blackhole.backend.imports.UUIDHasher;
import net.onelitefeather.blackhole.backend.imports.VanillaBanEntry;
import net.onelitefeather.blackhole.backend.imports.VanillaIpBanEntry;
import net.onelitefeather.blackhole.backend.imports.dto.VanillaImportDTO;
import io.micronaut.core.type.Argument;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.casetimeline.CaseTimeline;
import net.onelitefeather.blackhole.backend.casetimeline.CaseTimeline;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.profile.ProfileCache;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileRepository;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEvents;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateEntity;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateEntity;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.section.dto.SectionDTO;
import net.onelitefeather.blackhole.backend.utils.IdGenerator;
import net.onelitefeather.phoca.metadata.Expirable;
import net.onelitefeather.phoca.metadata.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Imports vanilla Minecraft ban lists ({@code banned-players.json} / {@code banned-ips.json})
 * through the normal repository write path, so Phase-1 domain events fire and connectors/
 * dashboards see the import like any other write - not a side-channel bulk load.
 */
@Singleton
public class VanillaImportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VanillaImportService.class);
    private static final DateTimeFormatter VANILLA_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT);

    /**
     * Vanilla ban entries don't carry a staff UUID (only an operator display name), so imported
     * punishments use this well-defined, deterministic system identity as their source instead
     * of guessing at a real staff member.
     */
    public static final UUID SYSTEM_IMPORT_SOURCE = UUID.nameUUIDFromBytes("blackhole-vanilla-import".getBytes(StandardCharsets.UTF_8));

    /** Metadata key of the original vanilla reason, kept for staff once a template supplies the shown one. */
    public static final String IMPORTED_REASON = "importedReason";

    /** The reason of an entry that has none; also the key of such entries in a reason-to-template mapping. */
    public static final String DEFAULT_REASON = "Imported vanilla ban";

    private final JsonMapper jsonMapper;
    private final PunishmentRepository punishmentRepository;
    private final PunishmentProfileRepository profileRepository;
    private final DomainEventPublisher eventPublisher;
    private final ProfileCache profileCache;
    private final ImportTemplateResolver templateResolver;
    private final CaseTimeline caseTimeline;
    private final Clock clock;

    public VanillaImportService(
            JsonMapper jsonMapper,
            PunishmentRepository punishmentRepository,
            PunishmentProfileRepository profileRepository,
            DomainEventPublisher eventPublisher,
            ProfileCache profileCache,
            ImportTemplateResolver templateResolver,
            CaseTimeline caseTimeline,
            Clock clock
    ) {
        this.jsonMapper = jsonMapper;
        this.punishmentRepository = punishmentRepository;
        this.profileRepository = profileRepository;
        this.eventPublisher = eventPublisher;
        this.profileCache = profileCache;
        this.templateResolver = templateResolver;
        this.caseTimeline = caseTimeline;
        this.clock = clock;
    }

    /**
     * Imports the bans of a vanilla {@code banned-players.json}, each linked to a punishment template.
     *
     * <p>The section, and the reason a player is shown, come from the template; the original vanilla
     * reason is kept in the metadata as {@code importedReason}. An import is a record of an earlier ban,
     * not a new offence: it deducts no ELO and records no escalation step.</p>
     *
     * @param templateKey     the template every entry is linked to unless {@code reasonTemplates} says otherwise
     * @param reasonTemplates optional JSON object mapping an original reason (exact text) to a template key
     * @param actor           the verified caller, recorded as the principal that wrote the bans
     * @return the {@link VanillaImportDTO.Result}, or a {@link VanillaImportDTO.Error} (nothing written) for an
     * unknown template, a template in a mute section or a malformed mapping
     */
    public VanillaImportDTO importVanillaBans(
            byte[] bannedPlayersJson, byte[] bannedIpsJson, String templateKey, String reasonTemplates, boolean dryRun, Actor actor
    ) throws IOException {
        Map<String, String> mapping;
        try {
            mapping = parseMapping(reasonTemplates);
        } catch (IOException e) {
            return error(ErrorKind.VALIDATION, "reasonTemplates must be a JSON object of reason text to template key");
        }
        if (templateKey == null || templateKey.isBlank()) {
            return error(ErrorKind.VALIDATION, "templateKey is required: imported bans must be linked to a template");
        }

        Map<String, ImportTemplateResolver.Resolved> templates = new HashMap<>();
        for (String key : distinctKeys(templateKey, mapping)) {
            switch (this.templateResolver.resolve(key)) {
                case ImportTemplateResolver.Refused refused -> {
                    return error(refused.kind(), refused.message());
                }
                case ImportTemplateResolver.Resolved resolved -> templates.put(key, resolved);
            }
        }

        List<VanillaBanEntry> entries = this.jsonMapper.readValue(bannedPlayersJson, Argument.listOf(VanillaBanEntry.class));

        int imported = 0;
        int skippedExisting = 0;
        int invalid = 0;
        List<String> invalidEntries = new ArrayList<>();
        Map<List<String>, Integer> groups = new LinkedHashMap<>();

        for (VanillaBanEntry entry : entries) {
            UUID rawUuid;
            try {
                rawUuid = UUID.fromString(entry.uuid());
            } catch (IllegalArgumentException | NullPointerException e) {
                invalid++;
                invalidEntries.add("Invalid uuid for entry '" + entry.name() + "': " + entry.uuid());
                continue;
            }

            Optional<Long> expirationMillis;
            try {
                expirationMillis = parseExpiration(entry.expires());
            } catch (DateTimeParseException e) {
                invalid++;
                invalidEntries.add("Invalid expires date for '" + entry.name() + "': " + entry.expires());
                continue;
            }

            String reason = normalizedReason(entry.reason());
            String entryTemplateKey = mapping.getOrDefault(reason, templateKey);
            ImportTemplateResolver.Resolved resolved = templates.get(entryTemplateKey);
            PunishmentTemplateEntity template = resolved.template();
            SectionDTO.Response section = resolved.section();
            String sectionKey = section.key();

            String owner = UUIDHasher.hash(rawUuid);
            long createdMillis = parseCreated(entry.created());
            PunishmentProfileEntity profile = this.profileRepository.findById(owner).orElse(null);

            if (profile != null && (profile.getActive().containsKey(sectionKey) || alreadyImportedFromVanilla(profile, createdMillis))) {
                skippedExisting++;
                continue;
            }

            groups.merge(List.of(reason, entryTemplateKey), 1, Integer::sum);
            if (dryRun) {
                imported++;
                continue;
            }

            Map<String, Object> metaData = new HashMap<>();
            metaData.put(Metadata.META_DATA_KEY_CREATION_DATE, createdMillis);
            metaData.put(Metadata.META_DATA_KEY_UPDATE_DATE, createdMillis);
            metaData.put(IMPORTED_REASON, reason);
            metaData.put("importSource", "vanilla");
            expirationMillis.ifPresent(exp -> metaData.put(Expirable.META_DATA_KEY_EXPIRATION_DATE, exp));

            PunishmentEntity punishment = new PunishmentEntity(
                    IdGenerator.generateId(), SYSTEM_IMPORT_SOURCE, owner, sectionKey, expirationMillis.isEmpty(), createdMillis, template, metaData
            );
            punishment.setRecordedByOid(actor.oid());
            PunishmentEntity savedPunishment = this.punishmentRepository.save(punishment);

            boolean alreadyExpired = expirationMillis.isPresent() && expirationMillis.get() <= this.clock.millis();

            if (profile == null) {
                List<PunishmentEntity> history = new ArrayList<>();
                Map<String, PunishmentEntity> active = new HashMap<>();
                if (alreadyExpired) {
                    history.add(savedPunishment);
                } else {
                    active.put(sectionKey, savedPunishment);
                }
                profile = new PunishmentProfileEntity(owner, active, history, new HashMap<>());
                this.profileRepository.save(profile);
                this.eventPublisher.publish("profile.created", Map.of("owner", owner));
            } else {
                if (alreadyExpired) {
                    profile.getHistory().add(savedPunishment);
                } else {
                    profile.getActive().put(sectionKey, savedPunishment);
                }
                this.profileRepository.update(profile);
            }

            this.profileCache.evict(owner);
            Map<String, Object> created = PunishmentEvents.created(savedPunishment, section);
            created.put("importSource", "vanilla");
            this.eventPublisher.publish("punishment.created", created);
            // No scores and no step: an import is a record of an earlier ban, not an escalation.
            this.caseTimeline.applied(savedPunishment, null, null);

            imported++;
        }

        int ipsTotal = 0;
        if (bannedIpsJson != null) {
            List<VanillaIpBanEntry> ipEntries = this.jsonMapper.readValue(bannedIpsJson, Argument.listOf(VanillaIpBanEntry.class));
            ipsTotal = ipEntries.size();
            if (ipsTotal > 0) {
                LOGGER.info(
                        "Skipping {} banned-ips.json entr{} - Blackhole has no first-class IP-ban concept",
                        ipsTotal, ipsTotal == 1 ? "y" : "ies"
                );
            }
        }

        List<VanillaImportDTO.ReasonGroup> reasonGroups = groups.entrySet().stream()
                .map(group -> new VanillaImportDTO.ReasonGroup(group.getKey().get(0), group.getKey().get(1), group.getValue()))
                .toList();
        return new VanillaImportDTO.Result(dryRun, entries.size(), imported, skippedExisting, invalid, invalidEntries, ipsTotal, ipsTotal, reasonGroups);
    }

    private static Set<String> distinctKeys(String templateKey, Map<String, String> mapping) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(templateKey);
        keys.addAll(mapping.values());
        return keys;
    }

    private Map<String, String> parseMapping(String reasonTemplates) throws IOException {
        if (reasonTemplates == null || reasonTemplates.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> parsed = this.jsonMapper.readValue(reasonTemplates, Argument.mapOf(String.class, String.class));
            Map<String, String> normalized = new HashMap<>();
            parsed.forEach((reason, key) -> {
                if (key == null || key.isBlank()) {
                    throw new IllegalArgumentException("blank template key");
                }
                normalized.put(normalizedReason(reason), key);
            });
            return normalized;
        } catch (RuntimeException e) {
            throw new IOException("malformed reason mapping", e);
        }
    }

    /** The reason an entry is grouped by: trimmed, and {@link #DEFAULT_REASON} when it has none. */
    public static String normalizedReason(String reason) {
        return reason == null || reason.isBlank() ? DEFAULT_REASON : reason.trim();
    }

    private static VanillaImportDTO.Error error(ErrorKind kind, String message) {
        return new VanillaImportDTO.Error(message, kind);
    }

    /**
     * An already-expired legacy ban is written straight into {@code history} rather than the
     * active slot (see below), so checking only the active slot let re-importing the
     * same file duplicate one history entry per already-expired entry on every run. This also
     * catches an active ban that has since expired locally and rotated into history.
     *
     * <p>Matches on {@code createdMillis} (the source file's own {@code created} timestamp,
     * carried onto the punishment's {@link Metadata#META_DATA_KEY_CREATION_DATE}) rather than
     * merely "any import-sourced entry exists" - {@link #SYSTEM_IMPORT_SOURCE} is one constant
     * shared by every vanilla import ever run, so matching on source alone would permanently
     * block this player from ever receiving a genuinely new imported ban again (e.g. from a
     * later, updated {@code banned-players.json}) once they had a single prior import-sourced
     * entry. Matching on the entry's own creation timestamp scopes the skip to "this exact
     * source record was already imported."</p>
     */
    private boolean alreadyImportedFromVanilla(PunishmentProfileEntity profile, long createdMillis) {
        return profile.getHistory().stream().anyMatch(p -> isSameImportedEntry(p, createdMillis));
    }

    private boolean isSameImportedEntry(PunishmentEntity punishment, long createdMillis) {
        if (!SYSTEM_IMPORT_SOURCE.equals(punishment.getSource())) {
            return false;
        }
        Object existingCreated = punishment.getMetaData().get(Metadata.META_DATA_KEY_CREATION_DATE);
        return existingCreated instanceof Number number && number.longValue() == createdMillis;
    }

    private Optional<Long> parseExpiration(String expires) {
        if (expires == null || expires.isBlank() || "forever".equalsIgnoreCase(expires.trim())) {
            return Optional.empty();
        }
        return Optional.of(OffsetDateTime.parse(expires, VANILLA_DATE_FORMAT).toInstant().toEpochMilli());
    }

    private long parseCreated(String created) {
        if (created == null || created.isBlank()) {
            return this.clock.millis();
        }
        try {
            return OffsetDateTime.parse(created, VANILLA_DATE_FORMAT).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return this.clock.millis();
        }
    }
}
