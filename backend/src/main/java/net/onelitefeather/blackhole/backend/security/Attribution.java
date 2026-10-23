package net.onelitefeather.blackhole.backend.security;

import java.util.UUID;

/**
 * Who a write is attributed to and which verified principal recorded it.
 *
 * @param source        the UUID stored in the record's attribution column: the caller's {@code oid}, or the
 *                      in-game staff member the proxy vouches for ({@code onBehalfOf})
 * @param recordedByOid the {@code oid} of the token that made the call, always the verified principal
 */
public record Attribution(UUID source, UUID recordedByOid) {
}
