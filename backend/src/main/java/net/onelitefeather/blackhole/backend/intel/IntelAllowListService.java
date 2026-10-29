package net.onelitefeather.blackhole.backend.intel;

import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.audit.AuditAction;
import net.onelitefeather.blackhole.backend.audit.AuditTarget;
import net.onelitefeather.blackhole.backend.audit.StaffAuditRecorder;
import net.onelitefeather.blackhole.backend.intel.dto.AllowListDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import net.onelitefeather.blackhole.backend.security.Actor;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Adds a per-player exemption from the VPN and hosting policy. A moderator's exemption lasts at most 90 days; a
 * permanent one is admin only. The entry is keyed by the owner hash and carries no name and no address.
 */
@Singleton
public class IntelAllowListService {

    static final int MAX_MODERATOR_DAYS = 90;
    static final int REASON_MAX = 200;
    static final int MAX_PAGE_SIZE = 100;
    private static final long MAX_OFFSET = Integer.MAX_VALUE - MAX_PAGE_SIZE - 1L;
    private static final Pattern OWNER = Pattern.compile("^[a-fA-F0-9]{128}$");

    private final IntelRecordStore store;
    private final IntelPolicyCache cache;
    private final StaffAuditRecorder audit;
    private final Clock clock;

    public IntelAllowListService(IntelRecordStore store, IntelPolicyCache cache, StaffAuditRecorder audit, Clock clock) {
        this.store = store;
        this.cache = cache;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Exempts one player.
     *
     * @return the stored entry, or an error for a bad owner, reason or duration, or a permanent entry by a non-admin
     */
    public AllowListDTO add(AllowListDTO.CreateRequest request, Actor actor) {
        if (request.owner() == null || !OWNER.matcher(request.owner()).matches()) {
            return error("owner must be a sha-512 hash", ErrorKind.VALIDATION);
        }
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.isEmpty() || reason.length() > REASON_MAX) {
            return error("reason must be 1 to 200 characters", ErrorKind.VALIDATION);
        }
        Integer days = request.durationDays();
        if (days != null && (days < 1 || days > MAX_MODERATOR_DAYS)) {
            return error("durationDays must be 1 to 90", ErrorKind.VALIDATION);
        }
        if (days == null && !actor.admin()) {
            return error("only an admin may make an exemption permanent", ErrorKind.FORBIDDEN_DURATION);
        }
        long now = this.clock.millis();
        Long expiresAt = days == null ? null : now + Duration.ofDays(days).toMillis();
        IntelAllowEntry entry = new IntelAllowEntry(request.owner(), reason, actor.oid(), expiresAt, now);
        this.store.saveAllowEntry(entry);
        this.cache.reload();
        this.audit.record(actor.attribution(), actor, AuditAction.INTEL_ALLOW_LIST_ADD, AuditTarget.ofPlayer(request.owner()));
        return new AllowListDTO.Response(entry.ownerHash(), entry.reason(), entry.actorOid().toString(), entry.expiresAt(),
                entry.createdAt());
    }

    /**
     * Lists the exemptions, newest first. Expiry is judged at the server clock now; an entry whose expiry has passed is
     * shown as expired, which matches the login check.
     *
     * @return one page, or an error for a page or a size out of range
     */
    public AllowListDTO list(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            return error("page must be 0 or more and size 1 to 100", ErrorKind.VALIDATION);
        }
        if ((long) page * size > MAX_OFFSET) {
            return error("page is past the last readable page", ErrorKind.VALIDATION);
        }
        long now = this.clock.millis();
        List<IntelAllowEntry> rows = this.store.allowListPage(size + 1, page * size);
        boolean hasMore = rows.size() > size;
        List<AllowListDTO.Entry> entries = rows.stream()
                .limit(size)
                .map(entry -> new AllowListDTO.Entry(entry.ownerHash(), entry.reason(), entry.actorOid().toString(),
                        entry.expiresAt(), entry.createdAt(), entry.expiresAt() != null && now >= entry.expiresAt()))
                .toList();
        return new AllowListDTO.Page(entries, page, size, hasMore);
    }

    /**
     * Deletes one exemption. The policy cache forgets it at once and the removal is audited.
     *
     * @return the removed owner, or an error for a malformed owner, an owner with no exemption, or a moderator
     *         removing a permanent exemption (FORBIDDEN_DURATION, the same rule as creating one)
     */
    public AllowListDTO remove(String owner, Actor actor) {
        if (owner == null || !OWNER.matcher(owner).matches()) {
            return error("owner must be a sha-512 hash", ErrorKind.VALIDATION);
        }
        Optional<IntelAllowEntry> existing = this.store.findAllowEntry(owner);
        if (existing.isEmpty()) {
            return error("this player has no exemption", ErrorKind.NOT_FOUND);
        }
        if (existing.get().expiresAt() == null && !actor.admin()) {
            return error("only an admin may remove a permanent exemption", ErrorKind.FORBIDDEN_DURATION);
        }
        this.store.deleteAllowEntry(owner);
        this.cache.reload();
        this.audit.record(actor.attribution(), actor, AuditAction.INTEL_ALLOW_LIST_REMOVE, AuditTarget.ofPlayer(owner));
        return new AllowListDTO.Removed(owner);
    }

    private static AllowListDTO.Error error(String message, ErrorKind kind) {
        return new AllowListDTO.Error(message, kind);
    }
}
