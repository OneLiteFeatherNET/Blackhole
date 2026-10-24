package net.onelitefeather.blackhole.backend.clientrule;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.serde.annotation.Serdeable;
import net.onelitefeather.blackhole.backend.clientrule.dto.ClientRuleCatalogueDTO;

import java.time.Instant;
import java.util.List;

/** One proxy's catalogue as kept in the shared cache. The serializer omits empty lists, so they read back as null. */
@Serdeable
@ReflectiveAccess
public record ClientRuleCatalogueSnapshot(
        String proxyId,
        Instant reportedAt,
        @Nullable List<ClientRuleCatalogueDTO.Entry> brands,
        @Nullable List<ClientRuleCatalogueDTO.Entry> channels,
        @Nullable List<String> commands,
        @Nullable List<ClientRuleCatalogueDTO.ProtocolEntry> protocols
) {
}
