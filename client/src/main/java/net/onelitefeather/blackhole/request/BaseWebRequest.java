package net.onelitefeather.blackhole.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jetbrains.annotations.NotNull;

import java.net.http.HttpClient;

/**
 * The {@link BaseWebRequest} class is the base class which contains some common variables and methods for web requests.
 *
 * @author theEvilReaper
 * @version 1.0.0
 * @since 1.0.0
 */
public abstract class BaseWebRequest {

    protected static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new Jdk8Module())
            .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);

    protected final String baseUrl;
    protected final HttpClient httpClient;

    /**
     * Create a new instance of the BaseWebRequest.
     *
     * @param baseUrl    the base URL of the server
     * @param httpClient the HTTP client
     */
    protected BaseWebRequest(@NotNull String baseUrl, @NotNull HttpClient httpClient) {
        this.baseUrl = baseUrl;
        this.httpClient = httpClient;
    }

    /**
     * Creates a string representation of the object.
     *
     * @param object the object to map
     * @return the string representation
     */
    protected @NotNull String mapObjectToString(Object object) {
        try {
            return OBJECT_MAPPER.writeValueAsString(object);
        } catch (Exception exception) {
            throw new RuntimeException("Failed to map object to string", exception);
        }
    }

    /**
     * Build the URL for the request.
     *
     * @param path the path to the resource
     * @return the URL
     */
    protected @NotNull String buildUrl(@NotNull String path) {
        return baseUrl + path;
    }
}
