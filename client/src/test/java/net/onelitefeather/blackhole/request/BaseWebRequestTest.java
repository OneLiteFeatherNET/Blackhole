package net.onelitefeather.blackhole.request;

import net.onelitefeather.blackhole.objects.TestObject;
import net.onelitefeather.blackhole.objects.TestWebRequest;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.*;

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
