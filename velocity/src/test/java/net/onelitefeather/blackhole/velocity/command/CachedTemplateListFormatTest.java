package net.onelitefeather.blackhole.velocity.command;

import com.fasterxml.jackson.core.type.TypeReference;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.model.PunishTemplateDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The backend writes the template list to the shared cache as the JSON the API returns; the proxy
 * reads it back into the generated client model for tab completion. This pins that wire format.
 */
class CachedTemplateListFormatTest {

    @Test
    void theBackendsCachedJsonDeserializesIntoTheClientModel() throws Exception {
        UUID id = UUID.randomUUID();
        String json = "[{\"reason\":\"spam\",\"type\":\"CHAT\",\"eloDelta\":-10,\"identifier\":\"" + id + "\"}]";

        List<PunishTemplateDTO> templates = ApiClient.createDefaultObjectMapper().readValue(json, new TypeReference<List<PunishTemplateDTO>>() {
        });

        assertEquals(1, templates.size(), "the one cached template must be read back");
        assertEquals(id, templates.get(0).getIdentifier(), "the identifier must survive the round trip");
        assertEquals("spam", templates.get(0).getReason(), "the reason must survive the round trip");
    }
}
