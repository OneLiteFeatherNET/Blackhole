package net.onelitefeather.blackhole.backend.imports;

import io.micronaut.core.type.Argument;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.profile.CacheInvalidationPublisher;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileEntity;
import net.onelitefeather.blackhole.backend.profile.PunishmentProfileRepository;
import net.onelitefeather.blackhole.backend.punishment.PunishmentEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentRepository;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateEntity;
import net.onelitefeather.blackhole.backend.punishment.PunishmentTemplateRepository;
import net.onelitefeather.blackhole.backend.punishment.PunishType;
import net.onelitefeather.blackhole.backend.utils.IdGenerator;
import net.onelitefeather.blackhole.backend.utils.UUIDHasher;
import net.onelitefeather.phoca.metadata.Expirable;
import net.onelitefeather.phoca.metadata.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
    private static final UUID SYSTEM_IMPORT_SOURCE = UUID.nameUUIDFromBytes("blackhole-vanilla-import".getBytes(StandardCharsets.UTF_8));

    private static final String DEFAULT_REASON = "Imported vanilla ban";

    private final JsonMapper jsonMapper;
    private final PunishmentTemplateRepository templateRepository;
    private final PunishmentRepository punishmentRepository;
    private final PunishmentProfileRepository profileRepository;
    private final DomainEventPublisher eventPublisher;
    private final CacheInvalidationPublisher cacheInvalidationPublisher;

    public VanillaImportService(
            JsonMapper jsonMapper,
            PunishmentTemplateRepository templateRepository,
            PunishmentRepository punishmentRepository,
            PunishmentProfileRepository profileRepository,
            DomainEventPublisher eventPublisher,
            CacheInvalidationPublisher cacheInvalidationPublisher
    ) {
        this.jsonMapper = jsonMapper;
        this.templateRepository = templateRepository;
        this.punishmentRepository = punishmentRepository;
        this.profileRepository = profileRepository;
        this.eventPublisher = eventPublisher;
        this.cacheInvalidationPublisher = cacheInvalidationPublisher;
    }

    public VanillaImportResultDTO importVanillaBans(byte[] bannedPlayersJson, byte[] bannedIpsJson, boolean dryRun) throws IOException {
        List<VanillaBanEntry> entries = this.jsonMapper.readValue(bannedPlayersJson, Argument.listOf(VanillaBanEntry.class));

        int imported = 0;
        int skippedExisting = 0;
        int invalid = 0;
        List<String> invalidEntries = new ArrayList<>();
        Map<String, PunishmentTemplateEntity> templateCache = new HashMap<>();

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

            String owner = UUIDHasher.hash(rawUuid);
            PunishmentProfileEntity profile = this.profileRepository.findById(owner).orElse(null);

            if (profile != null && profile.getActiveBan() != null) {
                skippedExisting++;
                continue;
            }

            if (dryRun) {
                imported++;
                continue;
            }

            long createdMillis = parseCreated(entry.created());
            String reason = (entry.reason() == null || entry.reason().isBlank()) ? DEFAULT_REASON : entry.reason();
            PunishmentTemplateEntity template = findOrCreateTemplate(reason, templateCache);

            Map<String, Object> metaData = new HashMap<>();
            metaData.put(Metadata.META_DATA_KEY_CREATION_DATE, createdMillis);
            metaData.put(Metadata.META_DATA_KEY_UPDATE_DATE, createdMillis);
            expirationMillis.ifPresent(exp -> metaData.put(Expirable.META_DATA_KEY_EXPIRATION_DATE, exp));

            PunishmentEntity punishment = new PunishmentEntity(
                    IdGenerator.generateId(), SYSTEM_IMPORT_SOURCE, PunishType.NETWORK, null, template, metaData
            );
            PunishmentEntity savedPunishment = this.punishmentRepository.save(punishment);

            boolean alreadyExpired = expirationMillis.isPresent() && expirationMillis.get() <= System.currentTimeMillis();

            if (profile == null) {
                List<PunishmentEntity> history = new ArrayList<>();
                PunishmentEntity activeBan = null;
                if (alreadyExpired) {
                    history.add(savedPunishment);
                } else {
                    activeBan = savedPunishment;
                }
                profile = new PunishmentProfileEntity(owner, null, activeBan, history, new HashMap<>());
                this.profileRepository.save(profile);
                this.eventPublisher.publish("profile.created", Map.of("owner", owner));
            } else {
                if (alreadyExpired) {
                    profile.getHistory().add(savedPunishment);
                } else {
                    profile.setActiveBan(savedPunishment);
                }
                this.profileRepository.update(profile);
            }

            this.cacheInvalidationPublisher.invalidate(owner);
            this.eventPublisher.publish("punishment.created", Map.of(
                    "owner", owner,
                    "punishmentIdentifier", savedPunishment.getIdentifier(),
                    "templateIdentifier", template.getIdentifier().toString(),
                    "type", PunishType.NETWORK.toString(),
                    "importSource", "vanilla"
            ));

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

        return new VanillaImportResultDTO(dryRun, entries.size(), imported, skippedExisting, invalid, invalidEntries, ipsTotal, ipsTotal);
    }

    private PunishmentTemplateEntity findOrCreateTemplate(String reason, Map<String, PunishmentTemplateEntity> templateCache) {
        return templateCache.computeIfAbsent(reason, r -> this.templateRepository.findByReasonAndType(r, PunishType.NETWORK)
                .orElseGet(() -> this.templateRepository.save(new PunishmentTemplateEntity(
                        null, r, PunishType.NETWORK, 0, Map.of("imported", true, "importSource", "vanilla")
                ))));
    }

    private Optional<Long> parseExpiration(String expires) {
        if (expires == null || expires.isBlank() || "forever".equalsIgnoreCase(expires.trim())) {
            return Optional.empty();
        }
        return Optional.of(OffsetDateTime.parse(expires, VANILLA_DATE_FORMAT).toInstant().toEpochMilli());
    }

    private long parseCreated(String created) {
        if (created == null || created.isBlank()) {
            return System.currentTimeMillis();
        }
        try {
            return OffsetDateTime.parse(created, VANILLA_DATE_FORMAT).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return System.currentTimeMillis();
        }
    }
}
