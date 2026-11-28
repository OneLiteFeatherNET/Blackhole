package net.onelitefeather.blackhole.backend.inbox;

import io.micronaut.core.annotation.Nullable;
import net.onelitefeather.blackhole.backend.casetimeline.ActorTrust;

import java.util.UUID;

/**
 * The newest case event of one punishment, as the inbox's changed section reads it. The actor's display name is joined from
 * the staff actor, so it is {@code null} once that person has been anonymized.
 *
 * @param shortId   the short reference of the punishment, or {@code null} when it is gone
 * @param appealId  the appeal identifier the event names, or {@code null}
 * @param actorName the staff display name of the actor, or {@code null}
 */
public record LatestChange(String punishmentIdentifier, @Nullable String shortId, String type, @Nullable String appealId,
                           long occurredAt, ActorTrust actorTrust, @Nullable UUID actorOid, @Nullable String actorName) {
}
