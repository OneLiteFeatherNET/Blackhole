package net.onelitefeather.blackhole.backend.cloudnet;

import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import net.onelitefeather.blackhole.backend.cloudnet.dto.CatalogueDTO;

import java.time.Instant;
import java.util.List;

/** What is stored per proxy in the shared cache: a validated report plus when it arrived. */
@Serdeable
@ReflectiveAccess
record CatalogueSnapshot(
        String proxyId,
        Instant reportedAt,
        List<CatalogueDTO.TaskEntry> tasks,
        List<String> groups,
        List<CatalogueDTO.ServiceEntry> services
) {
}
