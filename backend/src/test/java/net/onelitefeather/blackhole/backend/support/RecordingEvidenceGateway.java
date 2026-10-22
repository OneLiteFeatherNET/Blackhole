package net.onelitefeather.blackhole.backend.support;

import net.onelitefeather.blackhole.backend.evidence.service.PunishmentEvidenceGateway;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A {@link PunishmentEvidenceGateway} that records claims and answers counts a test sets. */
public final class RecordingEvidenceGateway implements PunishmentEvidenceGateway {

    /** One claim of pending evidence. */
    public record Claim(String owner, String correlationId, String punishmentIdentifier) {
    }

    private final List<Claim> claims = new ArrayList<>();
    private final Map<String, Long> counts = new HashMap<>();
    private RuntimeException failure;

    public List<Claim> claims() {
        return this.claims;
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
}
