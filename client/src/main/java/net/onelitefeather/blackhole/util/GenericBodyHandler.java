package net.onelitefeather.blackhole.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;

import java.net.http.HttpResponse;
import java.nio.charset.Charset;

public class GenericBodyHandler<T> implements HttpResponse.BodyHandler<T> {

    private final TypeReference<T> typeReference;
    private final ObjectMapper objectMapper;

    public GenericBodyHandler() {
        this.typeReference = new TypeReference<>() {
        };
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public HttpResponse.BodySubscriber<T> apply(@NotNull HttpResponse.ResponseInfo responseInfo) {
        if (responseInfo.statusCode() != 200) return HttpResponse.BodySubscribers.replacing(null);
        return HttpResponse.BodySubscribers.mapping(
                HttpResponse.BodySubscribers.ofString(Charset.defaultCharset()), this::parseJsonData
        );
    }

    /**
     * Parse the JSON data into a list of objects.
     *
     * @param data JSON data
     * @return List of objects
     */
    protected T parseJsonData(@NotNull String data) {
        try {
            return this.objectMapper.readValue(data, typeReference);
        } catch (Exception exception) {
            return null;
        }
    }
}
