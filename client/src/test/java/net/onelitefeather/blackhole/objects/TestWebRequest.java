package net.onelitefeather.blackhole.objects;

import net.onelitefeather.blackhole.request.BaseWebRequest;
import org.jetbrains.annotations.NotNull;

import java.net.http.HttpClient;

public class TestWebRequest extends BaseWebRequest {

    /**
     * Create a new instance of the BaseWebRequest.
     *
     * @param baseUrl    the base URL of the server
     * @param httpClient the HTTP client
     */
    public TestWebRequest(@NotNull String baseUrl, @NotNull HttpClient httpClient) {
        super(baseUrl, httpClient);
    }
}
