package net.onelitefeather.blackhole.backend.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.security.authentication.Authentication;
import jakarta.inject.Singleton;
import net.onelitefeather.blackhole.backend.imports.UUIDHasher;
import net.onelitefeather.blackhole.backend.integration.DetectionValidator.Verdict;
import net.onelitefeather.blackhole.backend.integration.dto.DetectionReason;
import net.onelitefeather.blackhole.backend.integration.dto.DetectionStatus;
import net.onelitefeather.blackhole.backend.integration.dto.IntegrationDetectionDTO;
import net.onelitefeather.blackhole.backend.response.ErrorKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Accepts a batch of detections from a registered integration: the caller is the registration (its client id, the
 * token's {@code azp}), the batch is capped, and every item gets its own verdict in request order. A bad item never stops
 * the others. Expected failures are returned as {@link IntegrationDetectionDTO.Error}, never thrown.
 *
 * <p>Caps: the body is measured in bytes before it is parsed, and more than {@value #MAX_ITEMS} items answer 413 before any
 * item is validated or stored. A body larger than {@value #MAX_BODY_BYTES} bytes answers 413 the same way. The body is
 * read as text so that an unknown field is an item error, not something the binder silently drops.
 *
 * <p>The raw player UUID is never stored and never logged: the owner is stored as its SHA-512 hash, the same value the
 * punishment API keys players by.
 */
@Singleton
public class IntegrationDetectionService {

    private static final Logger LOG = LoggerFactory.getLogger(IntegrationDetectionService.class);

    /** The body cap in bytes (UTF-8), checked before the body is parsed. */
    static final int MAX_BODY_BYTES = 64 * 1024;
    /** The item cap of one batch. */
    static final int MAX_ITEMS = 50;

    static final String AUTHORIZED_PARTY_CLAIM = "azp";
    static final String METER = "blackhole.integration.detections";

    private static final Pattern UUID_TEXT = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final IntegrationStore registrations;
    private final IntegrationDetectionStore detections;
    private final DetectionValidator validator;
    private final Clock clock;
    private final MeterRegistry meters;

    public IntegrationDetectionService(IntegrationStore registrations, IntegrationDetectionStore detections,
                                       DetectionValidator validator, Clock clock, MeterRegistry meters) {
        this.registrations = registrations;
        this.detections = detections;
        this.validator = validator;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * Ingests one batch.
     *
     * @param body   the raw request body: {@code {"detections": [...]}}
     * @param caller the verified token of the request; its {@code azp} names the registration
     */
    public IntegrationDetectionDTO ingest(String body, Authentication caller) {
        Optional<IntegrationStore.Stored> registration = registrationOf(caller);
        if (registration.isEmpty()) {
            return new IntegrationDetectionDTO.Error("no enabled integration registration exists for this client", ErrorKind.FORBIDDEN);
        }
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BODY_BYTES) {
            return tooLarge("the body is larger than " + MAX_BODY_BYTES + " bytes");
        }
        JsonNode items = batchOf(bytes);
        if (items == null) {
            return new IntegrationDetectionDTO.Error("the body must be an object with one array named detections", ErrorKind.VALIDATION);
        }
        if (items.size() > MAX_ITEMS) {
            return tooLarge("a batch holds at most " + MAX_ITEMS + " detections");
        }
        return new IntegrationDetectionDTO.Response(answer(registration.get(), items));
    }

    private List<IntegrationDetectionDTO.ItemResult> answer(IntegrationStore.Stored registration, JsonNode items) {
        Instant now = this.clock.instant();
        long receivedAt = this.clock.millis();
        List<IntegrationDetectionDTO.ItemResult> results = new ArrayList<>(items.size());
        int accepted = 0;
        int duplicate = 0;
        int rejected = 0;
        int index = 0;
        for (JsonNode item : items) {
            IntegrationDetectionDTO.ItemResult result = itemResult(index++, item, registration, now, receivedAt);
            results.add(result);
            switch (result.status()) {
                case ACCEPTED -> accepted++;
                case DUPLICATE -> duplicate++;
                case REJECTED -> rejected++;
            }
        }
        LOG.info("Integration registration {} ({}) submitted {} detection(s): {} accepted, {} duplicate, {} rejected",
                registration.id(), registration.spec().sourceSystem(), items.size(), accepted, duplicate, rejected);
        return results;
    }

    private IntegrationDetectionDTO.ItemResult itemResult(int index, JsonNode item, IntegrationStore.Stored registration,
                                                          Instant now, long receivedAt) {
        String detectionId = detectionIdOf(item);
        Verdict verdict = this.validator.validate(item, registration.spec(), now);
        return switch (verdict) {
            case Verdict.Rejected rejected -> outcome(index, detectionId, DetectionStatus.REJECTED, rejected.reason());
            case Verdict.Valid valid -> {
                DetectionValidator.Detection detection = valid.detection();
                boolean inserted = this.detections.insertIfAbsent(new IntegrationDetectionStore.NewDetection(registration.id(),
                        detection.detectionId(), detection.kind(), detection.schemaVersion(), ownerHash(detection.owner()),
                        detection.server(), detection.occurredAt().toEpochMilli(), receivedAt, detection.severity(),
                        detection.summary(), payloadJson(detection.payload())));
                yield inserted
                        ? outcome(index, detectionId, DetectionStatus.ACCEPTED, null)
                        : outcome(index, detectionId, DetectionStatus.DUPLICATE, null);
            }
        };
    }

    private IntegrationDetectionDTO.ItemResult outcome(int index, String detectionId, DetectionStatus status, DetectionReason reason) {
        Counter.builder(METER)
                .description("Integration detections by outcome; the reason is the rejection code, or none")
                .tag("outcome", status.name())
                .tag("reason", reason == null ? "none" : reason.name())
                .register(this.meters)
                .increment();
        return new IntegrationDetectionDTO.ItemResult(index, detectionId, status, reason);
    }

    private Optional<IntegrationStore.Stored> registrationOf(Authentication caller) {
        Object azp = caller == null ? null : caller.getAttributes().get(AUTHORIZED_PARTY_CLAIM);
        if (!(azp instanceof String clientId) || clientId.isBlank()) {
            return Optional.empty();
        }
        return this.registrations.findByClientId(clientId);
    }

    /** The items of a batch, or null when the body is not {@code {"detections": [...]}} exactly. */
    static JsonNode batchOf(byte[] body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (IOException e) {
            return null;
        }
        if (root == null || !root.isObject() || root.size() != 1 || !root.has("detections") || !root.get("detections").isArray()) {
            return null;
        }
        return root.get("detections");
    }

    /** The detection id of an item, echoed in its result only when it is a UUID; never anything else. */
    private static String detectionIdOf(JsonNode item) {
        JsonNode id = item.get("detectionId");
        return id != null && id.isTextual() && UUID_TEXT.matcher(id.asText()).matches() ? id.asText() : null;
    }

    static String ownerHash(UUID owner) {
        return UUIDHasher.hash(owner);
    }

    private static String payloadJson(JsonNode payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a validated payload cannot be serialised", e);
        }
    }

    private static IntegrationDetectionDTO.Error tooLarge(String reason) {
        return new IntegrationDetectionDTO.Error("batch refused, nothing was stored: " + reason, ErrorKind.PAYLOAD_TOO_LARGE);
    }
}
