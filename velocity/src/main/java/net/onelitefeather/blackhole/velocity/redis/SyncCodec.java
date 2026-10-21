package net.onelitefeather.blackhole.velocity.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.model.PunishTemplateResponse;
import net.onelitefeather.blackhole.velocity.section.SectionInfo;
import net.onelitefeather.blackhole.client.model.SectionResponse;

import java.util.List;
import java.util.Optional;

/**
 * Reads what the backend writes to Redis: the punishment sync messages (keys and channel) and the
 * shared-cache lists of sections and templates. Unknown fields are ignored so the backend can add
 * fields without breaking running proxies.
 */
public final class SyncCodec {

    private final ObjectMapper messages = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final ObjectMapper models = ApiClient.createDefaultObjectMapper();

    /**
     * @throws JsonProcessingException if the text is not a sync message
     */
    public PunishmentSyncMessage message(String json) throws JsonProcessingException {
        return this.messages.readValue(json, PunishmentSyncMessage.class);
    }

    /**
     * @return the message, or empty if the text is not a sync message
     */
    public Optional<PunishmentSyncMessage> tryMessage(String json) {
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(message(json));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    /**
     * @return the sections of a shared-cache entry, empty if it is not a section list
     */
    public Optional<List<SectionInfo>> sections(String json) {
        try {
            List<SectionResponse> sections = this.models.readValue(json, new TypeReference<List<SectionResponse>>() {
            });
            return Optional.of(sections.stream().map(SectionInfo::of).toList());
        } catch (JsonProcessingException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * @return the templates of a shared-cache entry, empty if it is not a template list
     */
    public Optional<List<PunishTemplateResponse>> templates(String json) {
        try {
            return Optional.of(this.models.readValue(json, new TypeReference<List<PunishTemplateResponse>>() {
            }));
        } catch (JsonProcessingException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
