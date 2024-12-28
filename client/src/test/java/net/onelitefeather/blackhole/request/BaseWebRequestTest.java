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

    @Test
    void createTemplate() {
        BlackholeClient blackholeClient = BlackholeClient.newClient("http://localhost:8080");
        TemplateWebRequests templateWebRequests = blackholeClient.templateRequests();
        PunishTemplate yolo = templateWebRequests.add(PunishTemplate.builder().translatable().duration(Duration.ofSeconds(30)).reason("YOLO").type(PunishType.NETWORK).build());
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(PunishTemplateSimpleModule.INSTANCE)
                .registerModule(PunishEntrySimpleModule.INSTANCE)
                .registerModule(PunishProfileSimpleModule.INSTANCE)
                .registerModule(new Jdk8Module());
        System.out.println(objectMapper.valueToTree(yolo));
        var profile = PunishProfile.builder().owner("bfd1b89d097121dbd22042c75fffb78f8871761898d764d1586f0640a5cbf46a8d8280c66f62f50efbe56fc1e535009603b9a43b2e3f7f43533ceae48fc8550d").build();
        PunishProfile add = blackholeClient.profileRequests().add(profile);
        System.out.println(objectMapper.valueToTree(add));
        PunishProfile punishProfile = blackholeClient.punishRequests().add(profile.owner(), yolo.identifier(), UUID.randomUUID());
        assertNotNull(punishProfile);
        System.out.println(objectMapper.valueToTree(punishProfile));
    }
}
