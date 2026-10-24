package net.onelitefeather.blackhole.backend.events;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micronaut.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What happens to one message pulled from a durable consumer: handled once, then always acknowledged. */
class NatsDeliveryTest {

    private final JsonMapper mapper = JsonMapper.createDefault();
    private final List<String> steps = new ArrayList<>();
    private final ListAppender<ILoggingEvent> log = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(NatsDelivery.class);

    @BeforeEach
    void captureLog() {
        this.log.start();
        this.logger.addAppender(this.log);
    }

    @AfterEach
    void releaseLog() {
        this.logger.detachAppender(this.log);
    }

    private byte[] body(DomainEvent event) throws Exception {
        return this.mapper.writeValueAsBytes(event);
    }

    private static final DomainEvent EVENT = new DomainEvent("id-1", "punishment.created", 5L, 1, Map.of("owner", "abc"));

    @Test
    void theHandlerSeesTheDecodedEventAndTheMessageIsAckedAfterwards() throws Exception {
        NatsDelivery.deliver(body(EVENT), this.mapper, "redis-sync",
                event -> this.steps.add("handled " + event.eventId()), () -> this.steps.add("ack"));

        assertEquals(List.of("handled id-1", "ack"), this.steps);
    }

    @Test
    void aFailingHandlerIsLoggedAndTheMessageIsStillAcked() throws Exception {
        NatsDelivery.deliver(body(EVENT), this.mapper, "redis-sync", event -> {
            throw new IllegalStateException("redis down");
        }, () -> this.steps.add("ack"));

        assertEquals(List.of("ack"), this.steps);
        assertTrue(this.log.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR
                && e.getFormattedMessage().contains("redis-sync")), "logged, got " + this.log.list);
    }

    @Test
    void aBodyThatIsNotAnEventIsLoggedSkippedAndAcked() {
        NatsDelivery.deliver("not json".getBytes(StandardCharsets.UTF_8), this.mapper, "redis-sync",
                event -> this.steps.add("handled"), () -> this.steps.add("ack"));

        assertEquals(List.of("ack"), this.steps, "a poison message must not be redelivered forever");
        assertTrue(this.log.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR), "logged, got " + this.log.list);
    }
}
