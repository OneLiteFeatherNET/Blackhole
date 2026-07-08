package net.onelitefeather.blackhole.backend.playerresolver;

import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.client.annotation.Client;

/**
 * NameMC has no official public API - this hits its regular player-search HTML page, the same
 * one a browser would load, since that's the only lookup surface it exposes. See
 * {@link NameMcPlayerResolver} for why this is treated as best-effort only.
 */
@Client(id = "namemc", value = "https://namemc.com")
public interface NameMcApiClient {

    @Get("/search?q={query}&type=exact")
    String search(@QueryValue String query);
}
