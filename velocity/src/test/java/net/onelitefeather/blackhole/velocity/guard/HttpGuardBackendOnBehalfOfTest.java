package net.onelitefeather.blackhole.velocity.guard;

import net.onelitefeather.blackhole.client.model.AreaBanCreateRequest;
import net.onelitefeather.blackhole.velocity.auth.FakeHttpClient;
import net.onelitefeather.blackhole.velocity.auth.TokenSource;
import net.onelitefeather.blackhole.velocity.config.BlackholeApiClients;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every staff write names the executing staff member by Minecraft UUID, the same value the punishment calls send. */
class HttpGuardBackendOnBehalfOfTest {

    private static final UUID STAFF = UUID.fromString("00000000-0000-4000-8000-0000000057af");
    private static final String GUARD_STATE = "{\"manualLevel\":\"STRICT\",\"automaticLevel\":\"OFF\",\"effectiveLevel\":\"STRICT\",\"updatedAt\":1}";
    private static final String AREA_BAN = "{\"id\":\"ban-1\",\"kind\":\"ASN\",\"value\":\"16276\",\"reason\":\"spam\","
            + "\"source\":\"PROXY\",\"createdAt\":1,\"active\":true}";

    private static final TokenSource TOKEN = new TokenSource() {
        @Override
        public String token() {
            return "proxy-token";
        }

        @Override
        public void rejected(String token) {
        }
    };

    private static HttpGuardBackend backend(FakeHttpClient transport) {
        return new HttpGuardBackend(BlackholeApiClients.create("http://backend.test", TOKEN, transport));
    }

    @Test
    void aLevelChangeSendsTheStaffMembersUuid() throws Exception {
        FakeHttpClient transport = new FakeHttpClient().answer(200, GUARD_STATE);

        backend(transport).setLevel(STAFF, "STRICT");

        assertTrue(transport.bodyOf(0).contains("\"onBehalfOf\":\"" + STAFF + "\""),
                "the proxy names the executing staff member: " + transport.bodyOf(0));
    }

    @Test
    void anAreaBanCreateSendsTheStaffMembersUuid() throws Exception {
        FakeHttpClient transport = new FakeHttpClient().answer(200, AREA_BAN);

        backend(transport).createAreaBan(STAFF, new AreaBanCreateRequest().kind("ASN").value("16276").reason("spam"));

        assertTrue(transport.bodyOf(0).contains("\"onBehalfOf\":\"" + STAFF + "\""),
                "the proxy names the executing staff member: " + transport.bodyOf(0));
    }

    @Test
    void anAreaBanLiftSendsTheStaffMembersUuid() throws Exception {
        FakeHttpClient transport = new FakeHttpClient().answer(200, AREA_BAN);

        backend(transport).liftAreaBan(STAFF, "ban-1", "appeal accepted");

        assertTrue(transport.bodyOf(0).contains("\"onBehalfOf\":\"" + STAFF + "\""),
                "the proxy names the executing staff member: " + transport.bodyOf(0));
    }
}
