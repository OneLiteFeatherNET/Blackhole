package net.onelitefeather.blackhole.backend.casetimeline;

import io.micronaut.serde.ObjectMapper;
import net.onelitefeather.blackhole.backend.casetimeline.dto.CaseDTO;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The body of a bulk edit is read by the serde layer, before any service runs. A body that names a delegation field is refused
 * there, so the rule does not depend on which service or test calls it.
 */
class CaseBulkRequestDeserializationTest {

    private final ObjectMapper mapper = ObjectMapper.create(Map.of());

    @Test
    void aBodyWithoutDelegationIsRead() {
        String body = "{\"cases\":[{\"punishmentIdentifier\":\"p-1\",\"expectedVersion\":0}],\"clearAssignee\":false,\"status\":\"CLOSED\"}";

        assertDoesNotThrow(() -> this.mapper.readValue(body, CaseDTO.BulkRequest.class));
    }

    @Test
    void aBodyNamingOnBehalfOfIsRefused() {
        String body = "{\"cases\":[{\"punishmentIdentifier\":\"p-1\",\"expectedVersion\":0}],\"clearAssignee\":false,"
                + "\"status\":\"CLOSED\",\"onBehalfOf\":\"00000000-0000-4000-8000-000000000001\"}";

        assertThrows(Exception.class, () -> this.mapper.readValue(body, CaseDTO.BulkRequest.class));
    }
}
