package net.onelitefeather.blackhole.backend.kick;

import io.micronaut.core.annotation.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence of the two kick tables that are not punishments: the audited mass-kick action and the
 * acknowledgements the proxies report. A kick of one player is a {@code KICK} punishment row and
 * lives in the punishment repository.
 */
public interface KickStore {

    /**
     * One audited admin action.
     *
     * @param actorOid the verified {@code oid} of the administrator
     */
    record Action(UUID id, KickScope scope, String target, String reason, @Nullable UUID actorOid, long createdAt, long deadlineAt) {
    }

    /**
     * What one proxy reported for a kick. No owner hash is stored.
     *
     * @param proxyId the configured proxy name, not personal data
     */
    record Delivery(String proxyId, boolean found, boolean moved, long ackedAt) {
    }

    /**
     * Stores the action unless one with the same id exists.
     *
     * @return {@code true} if it was stored, {@code false} if the id was taken (nothing changed)
     */
    boolean insertAction(Action action);

    Optional<Action> findAction(UUID id);

    /**
     * Stores the proxy's answer. One row per (kick, proxy): answering again keeps one row and only ever
     * turns {@code found} and {@code moved} on, so a repeated answer changes nothing.
     */
    void recordDelivery(UUID subjectId, Delivery delivery);

    /** Every proxy's answer for the kick. */
    List<Delivery> deliveries(UUID subjectId);
}
