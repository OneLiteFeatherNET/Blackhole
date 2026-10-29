package net.onelitefeather.blackhole.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.onelitefeather.blackhole.client.api.CaseApi;
import net.onelitefeather.blackhole.client.api.DiscordTicketsApi;
import net.onelitefeather.blackhole.client.model.CaseUpdateRequest;
import net.onelitefeather.blackhole.client.model.StaffTicketFreezeRequest;
import org.junit.jupiter.api.Test;
import org.openapitools.jackson.nullable.JsonNullableModule;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The generated client of spec 0.0.49 exposes the Discord staff routes and the optional onBehalfOf field. */
class StaffTicketClientTest {

    private static final UUID STAFF = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JsonNullableModule());

    @Test
    void theCaseApiExposesTheStaffTicketFreezeRoute() {
        assertTrue(hasOperation(CaseApi.class, "setStaffTicketFreeze"), "PUT /case/{reference}/ticket/freeze is generated");
    }

    @Test
    void theDiscordApiExposesTheStaffLinkLookup() {
        assertTrue(hasOperation(DiscordTicketsApi.class, "lookupDiscordStaffLink"), "GET /discord/staff-links/{discordUserId} is generated");
    }

    @Test
    void theFreezeRequestSerializesOnBehalfOfWhenSet() throws Exception {
        StaffTicketFreezeRequest request = new StaffTicketFreezeRequest().frozen(true).onBehalfOf(STAFF);

        JsonNode json = this.mapper.readTree(this.mapper.writeValueAsString(request));

        assertEquals(STAFF.toString(), json.get("onBehalfOf").asText(), "the staff member travels as onBehalfOf: " + json);
        assertTrue(json.get("frozen").asBoolean(), "the lock flag travels with it");
    }

    @Test
    void theCaseUpdateRequestSerializesOnBehalfOfWhenSet() throws Exception {
        CaseUpdateRequest request = new CaseUpdateRequest().expectedVersion(3L).clearAssignee(false).onBehalfOf(STAFF);

        JsonNode json = this.mapper.readTree(this.mapper.writeValueAsString(request));

        assertEquals(STAFF.toString(), json.get("onBehalfOf").asText(), "a claim from Discord names the staff member");
    }

    private static boolean hasOperation(Class<?> api, String name) {
        return Arrays.stream(api.getMethods()).anyMatch(method -> method.getName().equals(name));
    }
}
