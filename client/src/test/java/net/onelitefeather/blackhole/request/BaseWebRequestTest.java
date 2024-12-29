package net.onelitefeather.blackhole.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import net.onelitefeather.blackhole.api.profile.PunishProfile;
import net.onelitefeather.blackhole.api.profile.PunishProfileSimpleModule;
import net.onelitefeather.blackhole.api.punish.PunishEntrySimpleModule;
import net.onelitefeather.blackhole.api.punish.PunishType;
import net.onelitefeather.blackhole.api.template.PunishTemplate;
import net.onelitefeather.blackhole.api.template.PunishTemplateSimpleModule;
import net.onelitefeather.blackhole.objects.TestObject;
import net.onelitefeather.blackhole.objects.TestWebRequest;
import net.onelitefeather.blackhole.request.template.TemplateWebRequests;
import net.onelitefeather.blackhole.web.BlackholeClient;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseWebRequestTest {

    @Test
    void testConstructor() {
        BaseWebRequest<TestObject> testWebRequest = new TestWebRequest("baseUrl/", HttpClient.newHttpClient());
        assertNotNull(testWebRequest);

        String url = testWebRequest.buildUrl("url");
        assertNotNull(url);
        assertEquals("baseUrl/url", url);
    }

    @Test
    void testObjectMapping() {
        BaseWebRequest<TestObject> testWebRequest = new TestWebRequest("baseUrl/", HttpClient.newHttpClient());
        assertNotNull(testWebRequest);

        TestObject testObject = new TestObject("data");
        String json = testWebRequest.mapObjectToString(testObject);
        assertNotNull(json);
        assertTrue(json.contains("data"));
    }
}
