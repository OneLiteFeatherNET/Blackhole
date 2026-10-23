package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.evidence.service.PunishmentEvidenceGateway;
import net.onelitefeather.blackhole.backend.security.Attribution;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A {@link PunishmentEvidenceGateway} that records claims and answers counts a test sets. */
public final class RecordingEvidenceGateway implements PunishmentEvidenceGateway {

    /** One claim of pending evidence. */
    public record Claim(String owner, String correlationId, String punishmentIdentifier) {
    }

    /** One full-lift of a punishment's evidence. */
    public record Lift(String punishmentIdentifier, UUID appealIdentifier) {
    }

    private final List<String> deleted = new ArrayList<>();
    private final List<String> revoked = new ArrayList<>();
    private final List<Lift> lifted = new ArrayList<>();
    private final List<Claim> claims = new ArrayList<>();
    private final Map<String, Long> counts = new HashMap<>();
    private RuntimeException failure;

    public List<Claim> claims() {
        return this.claims;
    }

    public List<String> deleted() {
        return this.deleted;
    }

    public List<String> revoked() {
        return this.revoked;
    }

    public List<Lift> lifted() {
        return this.lifted;
    }

    /** Sets the count answered for a punishment. */
    public void givenCount(String punishmentIdentifier, long count) {
        this.counts.put(punishmentIdentifier, count);
    }

    /** Makes every claim throw. */
    public void failClaims(RuntimeException failure) {
        this.failure = failure;
    }

    @Override
    public int claimPending(String owner, String correlationId, String punishmentIdentifier) {
        if (this.failure != null) {
            throw this.failure;
        }
        this.claims.add(new Claim(owner, correlationId, punishmentIdentifier));
        return 1;
    }

    @Override
    public long countValid(String punishmentIdentifier) {
        return this.counts.getOrDefault(punishmentIdentifier, 0L);
    }

    @Override
    public int markRevoked(String punishmentIdentifier, Attribution by) {
        if (this.failure != null) {
            throw this.failure;
        }
        this.revoked.add(punishmentIdentifier);
        return 1;
    }

    @Override
    public int markLifted(String punishmentIdentifier, UUID appealIdentifier, Attribution by) {
        if (this.failure != null) {
            throw this.failure;
        }
        this.lifted.add(new Lift(punishmentIdentifier, appealIdentifier));
        return 1;
    }

    @Override
    public int deleteForPunishment(String punishmentIdentifier) {
        this.deleted.add(punishmentIdentifier);
        return 1;
    }
}
