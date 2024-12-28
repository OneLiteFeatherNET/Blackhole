package net.onelitefeather.blackhole.util;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import net.onelitefeather.blackhole.api.profile.PunishProfileSimpleModule;
import net.onelitefeather.blackhole.api.punish.PunishEntrySimpleModule;
import net.onelitefeather.blackhole.api.template.PunishTemplateSimpleModule;
import org.jetbrains.annotations.NotNull;

import java.net.http.HttpResponse;
import java.nio.charset.Charset;

public class GenericBodyHandler<T> implements HttpResponse.BodyHandler<T> {

    private final JavaType typeReference;
    private final ObjectMapper objectMapper;

    public GenericBodyHandler(JavaType typeReference) {
        this.typeReference = typeReference;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule())
                .registerModule(PunishTemplateSimpleModule.INSTANCE)
                .registerModule(PunishEntrySimpleModule.INSTANCE)
                .registerModule(PunishProfileSimpleModule.INSTANCE)
                .registerModule(new Jdk8Module());
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
            return this.objectMapper.readValue(data, this.typeReference);
        } catch (Exception exception) {
            return null;
        }
    }
}
