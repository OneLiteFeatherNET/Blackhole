package net.onelitefeather.blackhole.backend.security;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.events.DomainEventPublisher;
import net.onelitefeather.blackhole.backend.audit.AuditAction;
import net.onelitefeather.blackhole.backend.audit.AuditTarget;
import net.onelitefeather.blackhole.backend.audit.StaffAuditRecorder;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.dto.StaffLinkDTO;
import net.onelitefeather.blackhole.backend.staffprofile.StaffAvatarJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Links a staff member's Entra identity ({@code oid}) to one Minecraft account, proven from both
 * sides: the signed-in person asks for a one-time code ({@link #issueCode}), the proxy confirms it
 * together with the UUID of the player who typed it in game ({@link #confirm}).
 *
 * <p>Wrong, expired and reused codes all answer with the same {@code INVALID_CODE} error, and there
 * is deliberately no failed-attempt counter or any other limiter (infra owns rate limiting). The
 * code is stored only as a hash and appears in no log line, event or error. No {@code @Transactional}
 * (unusable in this codebase): the one-to-one rule is the database's, so a racing confirmation fails
 * on a unique constraint and is answered {@code CONFLICT}, never with two links. Expired code rows
 * are deleted lazily on the next issue or confirm.</p>
 */
@Singleton
public class StaffLinkService {

    private static final Logger LOG = LoggerFactory.getLogger(StaffLinkService.class);

    static final String CREATED_EVENT = "staff-link.created";
    static final String REMOVED_EVENT = "staff-link.removed";

    /** Why a link was removed, as published in {@code staff-link.removed}. */
    enum RemovalReason {
        SELF, ADMIN, REPLACED, ANONYMIZED
    }

    private static final String INVALID_CODE = "the link code is invalid or expired";
    private static final int MAX_CODE_ATTEMPTS = 5;

    private final StaffLinkRepository links;
    private final StaffLinkCodeRepository codes;
    private final StaffActorRepository actors;
    private final StaffLinkCodeGenerator generator;
    private final DomainEventPublisher events;
    private final StaffLinkProperties properties;
    private final Clock clock;
    private final StaffAvatarJob avatars;
    private final StaffAuditRecorder audit;

    public StaffLinkService(
            StaffLinkRepository links,
            StaffLinkCodeRepository codes,
            StaffActorRepository actors,
            StaffLinkCodeGenerator generator,
            DomainEventPublisher events,
            StaffLinkProperties properties,
            Clock clock,
            StaffAvatarJob avatars,
            StaffAuditRecorder audit
    ) {
        this.links = links;
        this.codes = codes;
        this.actors = actors;
        this.generator = generator;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
        this.avatars = avatars;
        this.audit = audit;
    }

    /**
     * Issues the caller a new code and invalidates their previous one.
     *
     * @return the {@link StaffLinkDTO.CodeResponse}, or a {@link StaffLinkDTO.Error} (403) for a service principal
     */
    public StaffLinkDTO issueCode(Actor caller) {
        if (caller.kind() != ActorKind.HUMAN) {
            return new StaffLinkDTO.Error("only a signed-in staff member can link a Minecraft account", ErrorKind.FORBIDDEN);
        }
        long now = this.clock.millis();
        this.codes.deleteByExpiresAtLessThanEquals(now);

        String code = null;
        String hash = null;
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS && code == null; attempt++) {
            String candidate = this.generator.generate();
            String candidateHash = StaffLinkCodeGenerator.hash(candidate);
            Optional<StaffLinkCodeEntity> holder = this.codes.findByCodeHash(candidateHash);
            if (holder.isEmpty() || holder.get().getOid().equals(caller.oid())) {
                code = candidate;
                hash = candidateHash;
            }
        }
        if (code == null) {
            return new StaffLinkDTO.Error("could not issue a code, try again", ErrorKind.CONFLICT);
        }

        long expiresAt = now + this.properties.ttl().toMillis();
        Optional<StaffLinkCodeEntity> previous = this.codes.findById(caller.oid());
        if (previous.isPresent()) {
            StaffLinkCodeEntity row = previous.get();
            row.setCodeHash(hash);
            row.setExpiresAt(expiresAt);
            this.codes.update(row);
        } else {
            this.codes.save(new StaffLinkCodeEntity(caller.oid(), hash, expiresAt));
        }
        LOG.info("Issued a staff link code for {}", caller.oid());
        return new StaffLinkDTO.CodeResponse(StaffLinkCodeGenerator.display(code), expiresAt);
    }

    /**
     * Binds the Minecraft UUID the proxy vouches for to the {@code oid} the code was issued to,
     * replacing any earlier link of either side, and consumes the code.
     *
     * @return the {@link StaffLinkDTO.LinkedResponse}, or a {@link StaffLinkDTO.Error}: 403 for a caller that is
     * not the proxy, 400 without {@code onBehalfOf}, {@code INVALID_CODE} for a wrong, expired or used code,
     * 409 when a racing confirmation took the account
     */
    public StaffLinkDTO confirm(StaffLinkDTO.CreateRequest request, Actor proxy) {
        if (!proxy.proxy()) {
            return new StaffLinkDTO.Error("only the proxy can confirm a link code", ErrorKind.FORBIDDEN);
        }
        if (request.onBehalfOf() == null) {
            return new StaffLinkDTO.Error("onBehalfOf is required", ErrorKind.VALIDATION);
        }
        long now = this.clock.millis();
        this.codes.deleteByExpiresAtLessThanEquals(now);

        Optional<StaffLinkCodeEntity> live = StaffLinkCodeGenerator.normalize(request.code())
                .map(StaffLinkCodeGenerator::hash)
                .flatMap(this.codes::findByCodeHash)
                .filter(row -> row.getExpiresAt() > now);
        if (live.isEmpty()) {
            return new StaffLinkDTO.Error(INVALID_CODE, ErrorKind.INVALID_CODE);
        }
        UUID oid = live.get().getOid();
        UUID minecraftUuid = request.onBehalfOf();
        this.codes.delete(live.get());

        Optional<StaffLinkEntity> ofOid = this.links.findById(oid);
        Optional<StaffLinkEntity> ofUuid = this.links.findByMinecraftUuid(minecraftUuid);
        boolean alreadyThisPair = ofOid.isPresent() && ofOid.get().getMinecraftUuid().equals(minecraftUuid);
        StaffLinkEntity link = alreadyThisPair ? ofOid.get() : new StaffLinkEntity(oid, minecraftUuid, now, proxy.oid());
        if (!alreadyThisPair) {
            ofOid.ifPresent(old -> remove(old, RemovalReason.REPLACED, proxy.oid()));
            ofUuid.filter(old -> !old.getOid().equals(oid)).ifPresent(old -> remove(old, RemovalReason.REPLACED, proxy.oid()));
            try {
                this.links.save(link);
            } catch (RuntimeException raced) {
                LOG.warn("Staff link of {} conflicted with a concurrent confirmation", oid);
                return new StaffLinkDTO.Error("the Minecraft account was linked concurrently, try again", ErrorKind.CONFLICT);
            }
            LOG.info("Linked staff member {} to a Minecraft account", oid);
            this.events.publish(CREATED_EVENT, Map.of("oid", oid.toString(), "linkedBy", proxy.oid().toString()));
            this.avatars.onLinked(oid, minecraftUuid);
        }
        return new StaffLinkDTO.LinkedResponse(oid, displayName(oid).orElse(null), link.getMinecraftUuid(), link.getLinkedAt());
    }

    /** The caller's own link status; never contains a code. */
    public StaffLinkDTO status(Actor caller) {
        return this.links.findById(caller.oid())
                .<StaffLinkDTO>map(link -> new StaffLinkDTO.StatusResponse(true, link.getMinecraftUuid(), link.getLinkedAt()))
                .orElseGet(() -> new StaffLinkDTO.StatusResponse(false, null, null));
    }

    /** Whether a Minecraft account is linked and to whom, for the in-game status command. */
    public StaffLinkDTO byMinecraft(UUID minecraftUuid) {
        return this.links.findByMinecraftUuid(minecraftUuid)
                .<StaffLinkDTO>map(link -> new StaffLinkDTO.LookupResponse(true, displayName(link.getOid()).orElse(null)))
                .orElseGet(() -> new StaffLinkDTO.LookupResponse(false, null));
    }

    /** All links, oldest first, with the staff members' display names. */
    public StaffLinkDTO list() {
        List<StaffLinkDTO.Item> items = this.links.findAll().stream()
                .sorted(Comparator.comparingLong(StaffLinkEntity::getLinkedAt))
                .map(link -> new StaffLinkDTO.Item(link.getOid(), displayName(link.getOid()).orElse(null), link.getMinecraftUuid(), link.getLinkedAt()))
                .toList();
        return new StaffLinkDTO.ListResponse(items);
    }

    /** Removes the caller's own link. */
    public StaffLinkDTO unlinkSelf(Actor caller) {
        return unlink(caller.oid(), RemovalReason.SELF, caller.oid());
    }

    /** Removes the link of {@code oid}, audited with the admin's own {@code oid}. */
    public StaffLinkDTO unlink(UUID oid, Actor admin) {
        StaffLinkDTO result = unlink(oid, RemovalReason.ADMIN, admin.oid());
        if (result instanceof StaffLinkDTO.RemovedResponse) {
            this.audit.record(admin.attribution(), admin, AuditAction.STAFF_LINK_UNLINK, AuditTarget.none());
        }
        return result;
    }

    /** Removes the link of an anonymized staff actor, if there is one; a no-op otherwise. */
    void removeOnAnonymize(UUID oid, Actor admin) {
        this.links.findById(oid).ifPresent(link -> remove(link, RemovalReason.ANONYMIZED, admin.oid()));
    }

    /**
     * Adds the staff member the in-game {@code source} is currently linked to as {@code actorOid}. A web
     * caller's attribution (source equals the recording principal) is returned unchanged: its
     * {@code recordedByOid} already is the person. An unlinked UUID leaves {@code actorOid} NULL, and a
     * link created later never rewrites earlier records.
     */
    public Attribution withLinkedActor(Attribution attribution) {
        if (attribution.source().equals(attribution.recordedByOid())) {
            return attribution;
        }
        return attribution.withActorOid(linkedOid(attribution.source()).orElse(null));
    }

    /** The staff member a Minecraft account is currently linked to. */
    public Optional<UUID> linkedOid(UUID minecraftUuid) {
        return this.links.findByMinecraftUuid(minecraftUuid).map(StaffLinkEntity::getOid);
    }

    /** The Minecraft account a staff member is currently linked to. */
    public Optional<UUID> linkedMinecraft(UUID oid) {
        return this.links.findById(oid).map(StaffLinkEntity::getMinecraftUuid);
    }

    /** The recorded display name of a staff member; empty when unknown or anonymized. */
    public Optional<String> displayName(UUID oid) {
        return this.actors.findById(oid).map(StaffActorEntity::getDisplayName);
    }

    private StaffLinkDTO unlink(UUID oid, RemovalReason reason, UUID removedBy) {
        Optional<StaffLinkEntity> link = this.links.findById(oid);
        if (link.isEmpty()) {
            return new StaffLinkDTO.Error("there is no link for " + oid, ErrorKind.NOT_FOUND);
        }
        remove(link.get(), reason, removedBy);
        return new StaffLinkDTO.RemovedResponse(oid);
    }

    private void remove(StaffLinkEntity link, RemovalReason reason, UUID removedBy) {
        this.links.delete(link);
        this.avatars.onUnlinked(link.getOid());
        LOG.info("Removed the staff link of {} ({})", link.getOid(), reason);
        this.events.publish(REMOVED_EVENT, Map.of(
                "oid", link.getOid().toString(), "removedBy", removedBy.toString(), "reason", reason.name()));
    }
}
