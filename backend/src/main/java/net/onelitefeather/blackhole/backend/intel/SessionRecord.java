package net.onelitefeather.blackhole.backend.intel;

import java.util.UUID;

/**
 * One stored login attempt or session. {@code ip} and {@code playerUuid} are clear on purpose (decisions 3 and 10 of
 * add-player-intelligence); nothing published carries them. {@code ipToken} is null while the IP salt is unset.
 */
public record SessionRecord(
        UUID sessionId,
        String ownerHash,
        UUID playerUuid,
        String playerName,
        String ip,
        String ipToken,
        Long asn,
        String asOrg,
        String countryCode,
        String clientBrand,
        String clientVersion,
        Integer protocolVersion,
        String virtualHost,
        SessionOutcome outcome,
        long startedAt,
        Long endedAt
) {
}
