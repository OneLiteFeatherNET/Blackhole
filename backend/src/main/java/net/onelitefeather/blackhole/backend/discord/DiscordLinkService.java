package net.onelitefeather.blackhole.backend.discord;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.discord.dto.DiscordLinkDTO;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.audit.AuditAction;
import net.onelitefeather.blackhole.backend.audit.AuditTarget;
import net.onelitefeather.blackhole.backend.audit.StaffAuditRecorder;
import net.onelitefeather.blackhole.backend.security.Actor;
import net.onelitefeather.blackhole.backend.security.ActorKind;
import net.onelitefeather.blackhole.backend.security.StaffActorEntity;
import net.onelitefeather.blackhole.backend.security.StaffActorRepository;
import net.onelitefeather.blackhole.backend.security.StaffLinkCodeGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Links a staff member (Entra {@code oid}) or a player (hashed owner) to a Discord account, proven
 * from both sides: the person asks for a one-time code (in the panel, or in game through the proxy)
 * and types it as {@code /link <code>} in Discord, where the bot confirms it together with the
 * Discord user id ({@link #confirm}). It mirrors {@code StaffLinkService}.
 *
 * <p>Wrong, expired and reused codes all answer with the same {@code INVALID_CODE}, and there is
 * deliberately no failed-attempt counter or any other limiter (infra owns rate limiting). The code is
 * stored only as a hash and appears in no log line, event or error. A Discord user id is personal data:
 * it is stored only in {@link DiscordLinkEntity} (erasable) and never appears in a log line, an event
 * or a response. No {@code @Transactional} (unusable in this codebase): the one-to-one rule is the
 * database's, so a racing confirmation fails on a unique constraint and is answered {@code CONFLICT},
 * never with two links. Expired code rows are deleted lazily on the next issue or confirm.</p>
 */
@Singleton
public class DiscordLinkService {

    private static final Logger LOG = LoggerFactory.getLogger(DiscordLinkService.class);

    static final String CREATED_EVENT = "discord-link.created";
    static final String REMOVED_EVENT = "discord-link.removed";

    /** Why a link was removed, as published in {@code discord-link.removed}. */
    enum RemovalReason {
        SELF, ADMIN, REPLACED, ANONYMIZED, ERASED
    }

    private static final String INVALID_CODE = "the link code is invalid or expired";
    private static final Pattern DISCORD_USER_ID = Pattern.compile("^\\d{15,20}$");
    private static final Pattern OWNER = Pattern.compile("^[a-fA-F0-9]{128}$");
    private static final int MAX_CODE_ATTEMPTS = 5;

    private final DiscordLinkRepository links;
    private final DiscordLinkCodeRepository codes;
    private final StaffActorRepository actors;
    private final StaffLinkCodeGenerator generator;
    private final DomainEventPublisher events;
    private final DiscordLinkProperties properties;
    private final Clock clock;
    private final StaffAuditRecorder audit;

    public DiscordLinkService(
            DiscordLinkRepository links,
            DiscordLinkCodeRepository codes,
            StaffActorRepository actors,
            StaffLinkCodeGenerator generator,
            DomainEventPublisher events,
            DiscordLinkProperties properties,
            Clock clock,
            StaffAuditRecorder audit
    ) {
        this.audit = audit;
        this.links = links;
        this.codes = codes;
        this.actors = actors;
        this.generator = generator;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Issues the signed-in staff member a new code and invalidates their previous one.
     *
     * @return the {@link DiscordLinkDTO.CodeResponse}, or a {@link DiscordLinkDTO.Error} (403) for a service principal
     */
    public DiscordLinkDTO issueStaffCode(Actor caller) {
        if (caller.kind() != ActorKind.HUMAN) {
            return new DiscordLinkDTO.Error("only a signed-in staff member can link a Discord account", ErrorKind.FORBIDDEN);
        }
        return issue(DiscordLinkKind.STAFF, caller.oid().toString());
    }

    /**
     * Issues a player a new code, asked for by the proxy after the player typed {@code /blackhole discord}.
     *
     * @return the {@link DiscordLinkDTO.CodeResponse}, or a {@link DiscordLinkDTO.Error}: 403 for a caller that is
     * not the proxy, 400 for a missing or malformed owner
     */
    public DiscordLinkDTO issuePlayerCode(DiscordLinkDTO.PlayerCodeRequest request, Actor proxy) {
        if (!proxy.proxy()) {
            return new DiscordLinkDTO.Error("only the proxy can issue a player link code", ErrorKind.FORBIDDEN);
        }
        if (request.owner() == null || !OWNER.matcher(request.owner()).matches()) {
            return new DiscordLinkDTO.Error("owner must be a sha-512 hash", ErrorKind.VALIDATION);
        }
        return issue(DiscordLinkKind.PLAYER, request.owner().toLowerCase(Locale.ROOT));
    }

    private DiscordLinkDTO issue(DiscordLinkKind kind, String subject) {
        long now = this.clock.millis();
        this.codes.deleteByExpiresAtLessThanEquals(now);

        String ownerKey = DiscordLinkCodeEntity.ownerKey(kind, subject);
        String code = null;
        String hash = null;
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS && code == null; attempt++) {
            String candidate = this.generator.generate();
            String candidateHash = StaffLinkCodeGenerator.hash(candidate);
            Optional<DiscordLinkCodeEntity> holder = this.codes.findByCodeHash(candidateHash);
            if (holder.isEmpty() || holder.get().getOwnerKey().equals(ownerKey)) {
                code = candidate;
                hash = candidateHash;
            }
        }
        if (code == null) {
            return new DiscordLinkDTO.Error("could not issue a code, try again", ErrorKind.CONFLICT);
        }

        long expiresAt = now + this.properties.ttl().toMillis();
        Optional<DiscordLinkCodeEntity> previous = this.codes.findById(ownerKey);
        if (previous.isPresent()) {
            DiscordLinkCodeEntity row = previous.get();
            row.setCodeHash(hash);
            row.setExpiresAt(expiresAt);
            this.codes.update(row);
        } else {
            this.codes.save(new DiscordLinkCodeEntity(kind, subject, hash, expiresAt));
        }
        LOG.info("Issued a Discord link code for a {} subject", kind);
        return new DiscordLinkDTO.CodeResponse(StaffLinkCodeGenerator.display(code), expiresAt);
    }

    /**
     * Binds the Discord user the bot vouches for to the subject the code was issued to, replacing any
     * earlier link of either side, and consumes the code.
     *
     * @return the {@link DiscordLinkDTO.LinkedResponse}, or a {@link DiscordLinkDTO.Error}: 400 for a missing or
     * malformed Discord user id, {@code INVALID_CODE} for a wrong, expired or used code, 409 when a racing
     * confirmation took the account
     */
    public DiscordLinkDTO confirm(DiscordLinkDTO.ConfirmRequest request) {
        if (request.discordUserId() == null || !DISCORD_USER_ID.matcher(request.discordUserId()).matches()) {
            return new DiscordLinkDTO.Error("discordUserId must be a Discord user id", ErrorKind.VALIDATION);
        }
        long now = this.clock.millis();
        this.codes.deleteByExpiresAtLessThanEquals(now);

        Optional<DiscordLinkCodeEntity> live = StaffLinkCodeGenerator.normalize(request.code())
                .map(StaffLinkCodeGenerator::hash)
                .flatMap(this.codes::findByCodeHash)
                .filter(row -> row.getExpiresAt() > now);
        if (live.isEmpty()) {
            return new DiscordLinkDTO.Error(INVALID_CODE, ErrorKind.INVALID_CODE);
        }
        DiscordLinkKind kind = live.get().getKind();
        String subject = live.get().getSubject();
        String discordUserId = request.discordUserId();
        this.codes.delete(live.get());

        Optional<DiscordLinkEntity> ofSubject = this.links.findByKindAndSubject(kind, subject);
        Optional<DiscordLinkEntity> ofDiscord = this.links.findByKindAndDiscordUserId(kind, discordUserId);
        boolean alreadyThisPair = ofSubject.isPresent() && ofSubject.get().getDiscordUserId().equals(discordUserId);
        DiscordLinkEntity link = alreadyThisPair ? ofSubject.get()
                : new DiscordLinkEntity(UUID.randomUUID(), kind, subject, discordUserId, now);
        if (!alreadyThisPair) {
            ofSubject.ifPresent(old -> remove(old, RemovalReason.REPLACED));
            ofDiscord.filter(old -> !old.getSubject().equals(subject)).ifPresent(old -> remove(old, RemovalReason.REPLACED));
            try {
                this.links.save(link);
            } catch (RuntimeException raced) {
                LOG.warn("A Discord link conflicted with a concurrent confirmation");
                return new DiscordLinkDTO.Error("the account was linked concurrently, try again", ErrorKind.CONFLICT);
            }
            LOG.info("Linked a {} subject to a Discord account", kind);
            this.events.publish(CREATED_EVENT, Map.of("kind", kind.name(), "subject", subject));
        }
        return new DiscordLinkDTO.LinkedResponse(kind, displayName(link).orElse(null), link.getLinkedAt());
    }

    /** The caller's own link status; never contains a code or a Discord id. */
    public DiscordLinkDTO staffStatus(Actor caller) {
        return this.links.findByKindAndSubject(DiscordLinkKind.STAFF, caller.oid().toString())
                .<DiscordLinkDTO>map(link -> new DiscordLinkDTO.StatusResponse(true, link.getLinkedAt()))
                .orElseGet(() -> new DiscordLinkDTO.StatusResponse(false, null));
    }

    /** What a Discord user is linked to, for the bot's {@code /whoami}. */
    public DiscordLinkDTO whoami(String discordUserId) {
        List<DiscordLinkDTO.WhoamiEntry> entries = this.links.findByDiscordUserId(discordUserId).stream()
                .sorted(Comparator.comparing(DiscordLinkEntity::getKind))
                .map(link -> new DiscordLinkDTO.WhoamiEntry(link.getKind(), displayName(link).orElse(null), link.getLinkedAt()))
                .toList();
        return new DiscordLinkDTO.WhoamiResponse(entries);
    }

    /**
     * Every live staff link (Discord user id and time), oldest first. The bot adds exactly these accounts, when they also hold the staff role,
     * to ticket channels. Staff without a link are absent.
     */
    public DiscordLinkDTO staffLinks() {
        return new DiscordLinkDTO.StaffLinksResponse(this.links.findByKind(DiscordLinkKind.STAFF).stream()
                .sorted(Comparator.comparingLong(DiscordLinkEntity::getLinkedAt))
                .map(link -> new DiscordLinkDTO.StaffLink(link.getDiscordUserId(), link.getLinkedAt()))
                .toList());
    }

    /** All links, oldest first, with the staff members' display names. */
    public DiscordLinkDTO list() {
        List<DiscordLinkDTO.Item> items = this.links.findAll().stream()
                .sorted(Comparator.comparingLong(DiscordLinkEntity::getLinkedAt))
                .map(link -> new DiscordLinkDTO.Item(
                        link.getId(), link.getKind(), link.getSubject(), displayName(link).orElse(null), link.getLinkedAt()))
                .toList();
        return new DiscordLinkDTO.ListResponse(items);
    }

    /** Removes the caller's own staff link. */
    public DiscordLinkDTO unlinkStaff(Actor caller) {
        Optional<DiscordLinkEntity> link = this.links.findByKindAndSubject(DiscordLinkKind.STAFF, caller.oid().toString());
        if (link.isEmpty()) {
            return new DiscordLinkDTO.Error("there is no Discord link for the caller", ErrorKind.NOT_FOUND);
        }
        remove(link.get(), RemovalReason.SELF);
        return new DiscordLinkDTO.RemovedResponse(1);
    }

    /** Removes every link of a Discord user, for the bot's {@code /unlink}. */
    public DiscordLinkDTO unlinkDiscordUser(String discordUserId) {
        List<DiscordLinkEntity> found = this.links.findByDiscordUserId(discordUserId);
        if (found.isEmpty()) {
            return new DiscordLinkDTO.Error("this Discord account is not linked", ErrorKind.NOT_FOUND);
        }
        found.forEach(link -> remove(link, RemovalReason.SELF));
        return new DiscordLinkDTO.RemovedResponse(found.size());
    }

    /** Removes one link on an admin's say-so. */
    public DiscordLinkDTO adminRemove(UUID id, Actor admin) {
        Optional<DiscordLinkEntity> link = this.links.findById(id);
        if (link.isEmpty()) {
            return new DiscordLinkDTO.Error("there is no such Discord link", ErrorKind.NOT_FOUND);
        }
        remove(link.get(), RemovalReason.ADMIN);
        // The link is named by nothing: a Discord user id is not written to the audit log.
        this.audit.record(admin.attribution(), admin, AuditAction.DISCORD_LINK_REMOVE, AuditTarget.none());
        return new DiscordLinkDTO.RemovedResponse(1);
    }

    /** Removes the Discord link of an anonymized staff actor, if there is one; a no-op otherwise. */
    public void removeOnAnonymize(UUID oid) {
        this.links.findByKindAndSubject(DiscordLinkKind.STAFF, oid.toString())
                .ifPresent(link -> remove(link, RemovalReason.ANONYMIZED));
    }

    /** Removes the Discord link of an erased player profile, if there is one; a no-op otherwise. */
    public void removeOnErase(String owner) {
        this.links.findByKindAndSubject(DiscordLinkKind.PLAYER, owner.toLowerCase(Locale.ROOT))
                .ifPresent(link -> remove(link, RemovalReason.ERASED));
    }

    private Optional<String> displayName(DiscordLinkEntity link) {
        if (link.getKind() != DiscordLinkKind.STAFF) {
            return Optional.empty();
        }
        try {
            return this.actors.findById(UUID.fromString(link.getSubject())).map(StaffActorEntity::getDisplayName);
        } catch (IllegalArgumentException notAnOid) {
            return Optional.empty();
        }
    }

    private void remove(DiscordLinkEntity link, RemovalReason reason) {
        this.links.delete(link);
        LOG.info("Removed a {} Discord link ({})", link.getKind(), reason);
        this.events.publish(REMOVED_EVENT, Map.of(
                "kind", link.getKind().name(), "subject", link.getSubject(), "reason", reason.name()));
    }
}
