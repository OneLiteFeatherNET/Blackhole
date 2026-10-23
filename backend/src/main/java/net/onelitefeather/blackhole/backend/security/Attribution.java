package net.onelitefeather.blackhole.backend.security;

import io.micronaut.core.annotation.Nullable;

import java.util.UUID;

/**
 * Who a write is attributed to and which verified principal recorded it.
 *
 * @param source        the UUID stored in the record's attribution column: the caller's {@code oid}, or the
 *                      in-game staff member the proxy vouches for ({@code onBehalfOf})
 * @param recordedByOid the {@code oid} of the token that made the call, always the verified principal
 * @param actorOid      the {@code oid} the in-game {@code source} is linked to at the time of the write, or
 *                      {@code null} for a web caller (its {@code recordedByOid} already is the person) and for
 *                      an unlinked in-game staff member
 */
public record Attribution(UUID source, UUID recordedByOid, @Nullable UUID actorOid) {

    public Attribution(UUID source, UUID recordedByOid) {
        this(source, recordedByOid, null);
    }

    /** This attribution with the staff member the in-game source is linked to ({@code null} if unlinked). */
    public Attribution withActorOid(@Nullable UUID actorOid) {
        return new Attribution(this.source, this.recordedByOid, actorOid);
    }
}
